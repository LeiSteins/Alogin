package top.steins.autologin.network

import org.junit.Assert.assertEquals
import org.junit.Test
import top.steins.autologin.R

class AccountOverviewLoadStageTest {

    @Test
    fun unexpectedErrorMessages_identifyEveryAccountOverviewLoadStage() {
        val messages = SelfServiceRepository.AccountOverviewLoadStage.entries.associateWith {
            it.unexpectedErrorMessageRes
        }

        assertEquals(
            R.string.self_service_stage_request_sso,
            messages[SelfServiceRepository.AccountOverviewLoadStage.REQUEST_SSO_CREDENTIALS]
        )
        assertEquals(
            R.string.self_service_stage_parse_sso,
            messages[SelfServiceRepository.AccountOverviewLoadStage.PARSE_SSO_CREDENTIALS]
        )
        assertEquals(
            R.string.self_service_stage_open_session,
            messages[SelfServiceRepository.AccountOverviewLoadStage.OPEN_SELF_SERVICE_SESSION]
        )
        assertEquals(
            R.string.self_service_stage_request_account_page,
            messages[SelfServiceRepository.AccountOverviewLoadStage.REQUEST_ACCOUNT_PAGE]
        )
        assertEquals(
            R.string.self_service_stage_parse_account_page,
            messages[SelfServiceRepository.AccountOverviewLoadStage.PARSE_ACCOUNT_PAGE]
        )
        assertEquals(
            R.string.self_service_stage_request_device_list,
            messages[SelfServiceRepository.AccountOverviewLoadStage.REQUEST_DEVICE_LIST]
        )
        assertEquals(
            R.string.self_service_stage_parse_device_list,
            messages[SelfServiceRepository.AccountOverviewLoadStage.PARSE_DEVICE_LIST]
        )
        assertEquals(
            R.string.self_service_stage_build_overview,
            messages[SelfServiceRepository.AccountOverviewLoadStage.BUILD_ACCOUNT_OVERVIEW]
        )
    }
}
