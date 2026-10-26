use crate::domain::{Network, NetworkKind, usable_ipv4};
use std::{cmp::Reverse, ffi::c_void, net::Ipv4Addr, ptr};
use windows_sys::Win32::{
    Foundation::{ERROR_BUFFER_OVERFLOW, HANDLE, HWND},
    NetworkManagement::{
        IpHelper::*,
        Ndis::{IfOperStatusUp, NET_LUID_LH},
        WiFi::*,
    },
    Networking::WinSock::*,
    UI::WindowsAndMessaging::PostMessageW,
};

pub fn snapshot() -> Network {
    // A global best route can belong to a TUN adapter. Select a route on an
    // active physical interface so portal parameters describe the campus NIC.
    let mut bytes = 16 * 1024u32;
    for _ in 0..3 {
        // Windows structures require 8-byte alignment.
        let mut buffer = vec![0u64; (bytes as usize).div_ceil(8)];
        let first = buffer.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
        let code = unsafe {
            GetAdaptersAddresses(
                AF_INET as u32,
                GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER,
                ptr::null(),
                first,
                &mut bytes,
            )
        };
        if code == ERROR_BUFFER_OVERFLOW {
            continue;
        }
        if code != 0 {
            return Network::default();
        }
        let mut best: Option<RouteCandidate> = None;
        let mut adapter = first;
        while !adapter.is_null() {
            let current = unsafe { &*adapter };
            if let Some(candidate) = physical_route(current).filter(|candidate| {
                best.as_ref()
                    .is_none_or(|previous| candidate.priority() < previous.priority())
            }) {
                best = Some(candidate);
            }
            adapter = current.Next;
        }
        return best.map_or_else(Network::default, |route| Network {
            interface: route.interface,
            ipv4: route.ipv4,
            ssid: if route.kind == NetworkKind::Wifi {
                connected_ssid(route.interface)
            } else {
                None
            },
            kind: route.kind,
        });
    }
    Network::default()
}

struct RouteCandidate {
    interface: u32,
    ipv4: String,
    kind: NetworkKind,
    prefix_length: u8,
    metric: u64,
}
impl RouteCandidate {
    fn priority(&self) -> (Reverse<u8>, u64, u32) {
        // Match Windows route preference, with a stable tie-breaker.
        (Reverse(self.prefix_length), self.metric, self.interface)
    }
}

fn physical_kind(interface: &MIB_IF_ROW2) -> Option<NetworkKind> {
    // HardwareInterface is the first documented bit; TUN adapters can advertise
    // an Ethernet IfType too, so the type alone is insufficient.
    if interface.OperStatus != IfOperStatusUp
        || interface.InterfaceAndOperStatusFlags._bitfield & 1 == 0
    {
        return None;
    }
    match interface.Type {
        IF_TYPE_ETHERNET_CSMACD => Some(NetworkKind::Ethernet),
        IF_TYPE_IEEE80211 => Some(NetworkKind::Wifi),
        _ => None,
    }
}

fn physical_route(adapter: &IP_ADAPTER_ADDRESSES_LH) -> Option<RouteCandidate> {
    let index = unsafe { adapter.Anonymous1.Anonymous.IfIndex };
    if index == 0 {
        return None;
    }
    let mut interface = MIB_IF_ROW2 {
        InterfaceIndex: index,
        ..Default::default()
    };
    if unsafe { GetIfEntry2(&mut interface) } != 0 {
        return None;
    }
    let kind = physical_kind(&interface)?;
    let mut destination = SOCKADDR_INET::default();
    destination.Ipv4.sin_family = AF_INET;
    destination.Ipv4.sin_addr.S_un.S_addr = u32::from_ne_bytes([10, 21, 221, 98]);
    let mut route = MIB_IPFORWARD_ROW2::default();
    let mut source = SOCKADDR_INET::default();
    if unsafe {
        GetBestRoute2(
            ptr::null(),
            index,
            ptr::null(),
            &destination,
            0,
            &mut route,
            &mut source,
        )
    } != 0
        || route.InterfaceIndex != index
        || unsafe { source.si_family } != AF_INET
    {
        return None;
    }
    let ip = unsafe { source.Ipv4 };
    let ipv4 = Ipv4Addr::from(unsafe { ip.sin_addr.S_un.S_addr }.to_ne_bytes()).to_string();
    if !usable_ipv4(&ipv4) || !has_preferred_address(adapter, &ipv4) {
        return None;
    }
    Some(RouteCandidate {
        interface: index,
        ipv4,
        kind,
        prefix_length: route.DestinationPrefix.PrefixLength,
        metric: u64::from(route.Metric) + u64::from(adapter.Ipv4Metric),
    })
}

fn has_preferred_address(adapter: &IP_ADAPTER_ADDRESSES_LH, ipv4: &str) -> bool {
    let mut address = adapter.FirstUnicastAddress;
    while !address.is_null() {
        let entry = unsafe { &*address };
        if !entry.Address.lpSockaddr.is_null()
            && entry.Address.iSockaddrLength as usize >= std::mem::size_of::<SOCKADDR_IN>()
            && entry.DadState == IpDadStatePreferred
        {
            let ip = unsafe { &*entry.Address.lpSockaddr.cast::<SOCKADDR_IN>() };
            if ip.sin_family == AF_INET
                && Ipv4Addr::from(unsafe { ip.sin_addr.S_un.S_addr }.to_ne_bytes()).to_string()
                    == ipv4
            {
                return true;
            }
        }
        address = entry.Next;
    }
    false
}

struct Wlan(HANDLE);
impl Wlan {
    fn open() -> Option<Self> {
        let mut version = 0;
        let mut handle = ptr::null_mut();
        if unsafe { WlanOpenHandle(2, ptr::null(), &mut version, &mut handle) } == 0 {
            Some(Self(handle))
        } else {
            None
        }
    }
}
impl Drop for Wlan {
    fn drop(&mut self) {
        unsafe {
            WlanCloseHandle(self.0, ptr::null());
        }
    }
}
struct WlanMemory(*mut c_void);
impl Drop for WlanMemory {
    fn drop(&mut self) {
        unsafe {
            WlanFreeMemory(self.0);
        }
    }
}

fn connected_ssid(interface: u32) -> Option<String> {
    let wlan = Wlan::open()?;
    let mut list = ptr::null_mut();
    if unsafe { WlanEnumInterfaces(wlan.0, ptr::null(), &mut list) } != 0 || list.is_null() {
        return None;
    }
    let _list_memory = WlanMemory(list.cast());
    let items = unsafe {
        std::slice::from_raw_parts(
            (*list).InterfaceInfo.as_ptr(),
            (*list).dwNumberOfItems as usize,
        )
    };
    for item in items {
        let mut luid = NET_LUID_LH::default();
        let mut index = 0;
        if unsafe { ConvertInterfaceGuidToLuid(&item.InterfaceGuid, &mut luid) } != 0 {
            continue;
        }
        if unsafe { ConvertInterfaceLuidToIndex(&luid, &mut index) } != 0
            || index != interface
            || item.isState != wlan_interface_state_connected
        {
            continue;
        }
        let mut size = 0;
        let mut data = ptr::null_mut();
        if unsafe {
            WlanQueryInterface(
                wlan.0,
                &item.InterfaceGuid,
                wlan_intf_opcode_current_connection,
                ptr::null(),
                &mut size,
                &mut data,
                ptr::null_mut(),
            )
        } != 0
            || data.is_null()
        {
            return None;
        }
        let _data_memory = WlanMemory(data);
        if size as usize >= std::mem::size_of::<WLAN_CONNECTION_ATTRIBUTES>() {
            let attributes = unsafe { &*data.cast::<WLAN_CONNECTION_ATTRIBUTES>() };
            let ssid = &attributes.wlanAssociationAttributes.dot11Ssid;
            // Invalid UTF-8 SSIDs are intentionally ineligible for automatic login.
            return std::str::from_utf8(ssid.ucSSID.get(..ssid.uSSIDLength as usize)?)
                .ok()
                .map(str::to_owned);
        }
    }
    None
}

pub const NETWORK_MESSAGE: u32 = windows_sys::Win32::UI::WindowsAndMessaging::WM_APP + 2;

static REVISION: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

pub fn revision() -> u64 {
    REVISION.load(std::sync::atomic::Ordering::SeqCst)
}

fn changed() {
    REVISION.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
}

pub struct NetworkEvents {
    interface: HANDLE,
    address: HANDLE,
    route: HANDLE,
    wlan: Option<Wlan>,
}
impl NetworkEvents {
    pub fn subscribe(window: HWND) -> Result<Self, String> {
        let mut events = Self {
            interface: ptr::null_mut(),
            address: ptr::null_mut(),
            route: ptr::null_mut(),
            wlan: Wlan::open(),
        };
        let context = window.cast();
        let interface_error = unsafe {
            NotifyIpInterfaceChange(
                AF_INET,
                Some(interface_changed),
                context,
                false,
                &mut events.interface,
            )
        };
        let address_error = unsafe {
            NotifyUnicastIpAddressChange(
                AF_INET,
                Some(address_changed),
                context,
                false,
                &mut events.address,
            )
        };
        unsafe {
            NotifyRouteChange2(
                AF_INET,
                Some(route_changed),
                context,
                false,
                &mut events.route,
            );
        }
        if interface_error != 0 || address_error != 0 {
            return Err(crate::strings::SUBSCRIBE_FAILED.into());
        }
        if let Some(wlan) = &events.wlan {
            unsafe {
                WlanRegisterNotification(
                    wlan.0,
                    WLAN_NOTIFICATION_SOURCE_ACM,
                    1,
                    Some(wifi_changed),
                    context,
                    ptr::null(),
                    ptr::null_mut(),
                );
            }
        }
        Ok(events)
    }
}
impl Drop for NetworkEvents {
    fn drop(&mut self) {
        for handle in [self.interface, self.address, self.route] {
            if !handle.is_null() {
                unsafe {
                    CancelMibChangeNotify2(handle);
                }
            }
        }
        // WlanCloseHandle unregisters and waits for outstanding callbacks.
        self.wlan.take();
    }
}
unsafe extern "system" fn interface_changed(
    context: *const c_void,
    _: *const MIB_IPINTERFACE_ROW,
    _: MIB_NOTIFICATION_TYPE,
) {
    changed();
    unsafe {
        PostMessageW(context.cast_mut(), NETWORK_MESSAGE, 0, 0);
    }
}
unsafe extern "system" fn address_changed(
    context: *const c_void,
    _: *const MIB_UNICASTIPADDRESS_ROW,
    _: MIB_NOTIFICATION_TYPE,
) {
    changed();
    unsafe {
        PostMessageW(context.cast_mut(), NETWORK_MESSAGE, 0, 0);
    }
}
unsafe extern "system" fn route_changed(
    context: *const c_void,
    _: *const MIB_IPFORWARD_ROW2,
    _: MIB_NOTIFICATION_TYPE,
) {
    changed();
    unsafe {
        PostMessageW(context.cast_mut(), NETWORK_MESSAGE, 0, 0);
    }
}
unsafe extern "system" fn wifi_changed(_: *mut L2_NOTIFICATION_DATA, context: *mut c_void) {
    changed();
    unsafe {
        PostMessageW(context.cast(), NETWORK_MESSAGE, 0, 0);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn virtual_ethernet_and_disconnected_hardware_are_not_campus_interfaces() {
        let mut interface = MIB_IF_ROW2 {
            Type: IF_TYPE_ETHERNET_CSMACD,
            OperStatus: IfOperStatusUp,
            ..Default::default()
        };
        // Mihomo and Hyper-V can both report Ethernet without being hardware.
        assert_eq!(physical_kind(&interface), None);
        interface.InterfaceAndOperStatusFlags._bitfield = 1;
        assert_eq!(physical_kind(&interface), Some(NetworkKind::Ethernet));
        interface.Type = IF_TYPE_IEEE80211;
        assert_eq!(physical_kind(&interface), Some(NetworkKind::Wifi));
        interface.OperStatus = windows_sys::Win32::NetworkManagement::Ndis::IfOperStatusDown;
        assert_eq!(physical_kind(&interface), None);
        interface.OperStatus = IfOperStatusUp;
        interface.Type = IF_TYPE_TUNNEL;
        assert_eq!(physical_kind(&interface), None);
    }

    #[test]
    fn physical_routes_prefer_longest_prefix_then_metric_with_stable_ties() {
        let default = RouteCandidate {
            interface: 20,
            ipv4: "10.1.2.3".into(),
            kind: NetworkKind::Wifi,
            prefix_length: 0,
            metric: 30,
        };
        let mut other = RouteCandidate {
            interface: 21,
            ipv4: "10.4.5.6".into(),
            kind: NetworkKind::Ethernet,
            prefix_length: 8,
            metric: 500,
        };
        assert!(other.priority() < default.priority());
        other.prefix_length = 0;
        assert!(default.priority() < other.priority());
        other.metric = 10;
        assert!(other.priority() < default.priority());
        other.metric = default.metric;
        assert!(default.priority() < other.priority());
        let mut routes = [other, default];
        routes.sort_by_key(RouteCandidate::priority);
        assert_eq!(routes[0].interface, 20);
        routes.reverse();
        routes.sort_by_key(RouteCandidate::priority);
        assert_eq!(routes[0].interface, 20);
    }

    #[test]
    fn route_source_must_be_a_preferred_address_on_the_selected_adapter() {
        let mut ip = SOCKADDR_IN {
            sin_family: AF_INET,
            ..Default::default()
        };
        ip.sin_addr.S_un.S_addr = u32::from_ne_bytes([10, 1, 2, 3]);
        let mut address = IP_ADAPTER_UNICAST_ADDRESS_LH {
            Address: SOCKET_ADDRESS {
                lpSockaddr: (&mut ip as *mut SOCKADDR_IN).cast(),
                iSockaddrLength: std::mem::size_of_val(&ip) as i32,
            },
            DadState: IpDadStatePreferred,
            ..Default::default()
        };
        let check = |address: &mut IP_ADAPTER_UNICAST_ADDRESS_LH, ipv4: &str| {
            has_preferred_address(
                &IP_ADAPTER_ADDRESSES_LH {
                    FirstUnicastAddress: address,
                    ..Default::default()
                },
                ipv4,
            )
        };
        assert!(check(&mut address, "10.1.2.3"));
        assert!(!check(&mut address, "198.18.0.1"));
        address.DadState = IpDadStateDeprecated;
        assert!(!check(&mut address, "10.1.2.3"));
        address.DadState = IpDadStatePreferred;
        address.Address.iSockaddrLength = 0;
        assert!(!check(&mut address, "10.1.2.3"));
        address.Address.lpSockaddr = ptr::null_mut();
        assert!(!check(&mut address, "10.1.2.3"));
        assert!(!has_preferred_address(
            &IP_ADAPTER_ADDRESSES_LH::default(),
            "10.1.2.3"
        ));
    }
}
