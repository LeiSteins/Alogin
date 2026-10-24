package top.steins.autologin.network

import top.steins.autologin.domain.port.AccountSessionGateway
import top.steins.autologin.domain.port.SeamlessGateway

/** 仅用于应用组装和资源释放；账号与无感知功能共用同一实现。 */
interface SelfServiceGateway : AccountSessionGateway, SeamlessGateway, AutoCloseable {
    override fun close() = Unit
}
