use crate::domain::{Network, usable_ipv4};
use std::{ffi::c_void, ptr};
use windows_sys::Win32::{
    Foundation::{ERROR_BUFFER_OVERFLOW, HANDLE, HWND},
    NetworkManagement::{IpHelper::*, Ndis::NET_LUID_LH, WiFi::*},
    Networking::WinSock::*,
    UI::WindowsAndMessaging::PostMessageW,
};

pub fn snapshot() -> Network {
    // Match the route to the fixed campus portal instead of selecting an arbitrary NIC.
    let mut index = 0;
    if unsafe { GetBestInterface(u32::from_ne_bytes([10, 21, 221, 98]), &mut index) } != 0 {
        return Network::default();
    }
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
        let mut adapter = first;
        while !adapter.is_null() {
            let current = unsafe { &*adapter };
            if unsafe { current.Anonymous1.Anonymous.IfIndex } == index {
                let mut address = current.FirstUnicastAddress;
                while !address.is_null() {
                    let entry = unsafe { &*address };
                    if !entry.Address.lpSockaddr.is_null()
                        && entry.Address.iSockaddrLength as usize
                            >= std::mem::size_of::<SOCKADDR_IN>()
                        && entry.DadState == IpDadStatePreferred
                    {
                        let ip = unsafe { &*entry.Address.lpSockaddr.cast::<SOCKADDR_IN>() };
                        if ip.sin_family == AF_INET {
                            let ipv4 = std::net::Ipv4Addr::from(
                                unsafe { ip.sin_addr.S_un.S_addr }.to_ne_bytes(),
                            )
                            .to_string();
                            if usable_ipv4(&ipv4) {
                                return Network {
                                    interface: index,
                                    ipv4,
                                    ssid: connected_ssid(index),
                                };
                            }
                        }
                    }
                    address = entry.Next;
                }
            }
            adapter = current.Next;
        }
        break;
    }
    Network::default()
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
    unsafe {
        PostMessageW(context.cast_mut(), NETWORK_MESSAGE, 0, 0);
    }
}
unsafe extern "system" fn address_changed(
    context: *const c_void,
    _: *const MIB_UNICASTIPADDRESS_ROW,
    _: MIB_NOTIFICATION_TYPE,
) {
    unsafe {
        PostMessageW(context.cast_mut(), NETWORK_MESSAGE, 0, 0);
    }
}
unsafe extern "system" fn route_changed(
    context: *const c_void,
    _: *const MIB_IPFORWARD_ROW2,
    _: MIB_NOTIFICATION_TYPE,
) {
    unsafe {
        PostMessageW(context.cast_mut(), NETWORK_MESSAGE, 0, 0);
    }
}
unsafe extern "system" fn wifi_changed(_: *mut L2_NOTIFICATION_DATA, context: *mut c_void) {
    unsafe {
        PostMessageW(context.cast(), NETWORK_MESSAGE, 0, 0);
    }
}
