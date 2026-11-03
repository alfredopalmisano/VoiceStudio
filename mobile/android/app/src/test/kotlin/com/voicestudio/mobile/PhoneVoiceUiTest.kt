package com.voicestudio.mobile

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.voicestudio.mobile.data.*
import com.voicestudio.mobile.designsystem.VoiceStudioTheme
import com.voicestudio.mobile.features.LocalL10n
import com.voicestudio.mobile.features.PhoneState
import com.voicestudio.mobile.features.PhoneStudio
import com.voicestudio.mobile.features.StudioState
import com.voicestudio.mobile.features.StudioViewModel
import com.voicestudio.mobile.features.voices.VoicesScreen
import com.voicestudio.mobile.l10n.L10n
import com.voicestudio.mobile.local.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.mockito.Mockito.*
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PhoneVoiceUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun cloneFormShowsPhoneProgressAndProtectsTheSampleUntilCancellation() {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val l=L10n.load(app,"en")
        val models=listOf("asr","clone").map {capability ->
            PhoneModel("fixture-$capability",capability,"sherpa-onnx","org/model","a".repeat(40),
                emptyList(),1,listOf("en"),1.0,ModelLicence("mit","commercial-ok",false,"","","b".repeat(64)))
        }
        val phoneState=MutableStateFlow(PhoneState(models=models,installed=models.map {it.id}.toSet(),loading=false))
        val phone=mock(PhoneStudio::class.java)
        `when`(phone.state).thenReturn(phoneState)
        `when`(phone.ramBytes).thenReturn(16_000_000_000L)
        val vm=mock(StudioViewModel::class.java)
        `when`(vm.phone).thenReturn(phone)
        val hostState=MutableStateFlow(StudioState(loading=false))
        `when`(vm.state).thenReturn(hostState)
        `when`(vm.preferences).thenReturn(MutableStateFlow(Preferences()))
        compose.setContent {
            CompositionLocalProvider(LocalL10n provides l) {
                VoiceStudioTheme {VoicesScreen(vm,initialCreating=true)}
            }
        }
        compose.onNodeWithText(l.t("mobile.voices.pick_file")).performScrollTo().assertIsEnabled()
        val progress=SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        for(value in listOf(0f,.65f)) {
            compose.runOnIdle {phoneState.value=phoneState.value.copy(progress=value)}
            compose.onNodeWithContentDescription(l.t("mobile.ios.progress")).assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo(value,0f..1f)))
            compose.onNodeWithText(l.t("mobile.voices.pick_file")).assertIsNotEnabled()
            compose.onNodeWithText(l.t("mobile.voices.record")).assertIsNotEnabled()
            compose.onNodeWithText(l.t("mobile.voices.new_clone")).assertIsNotEnabled()
        }
        compose.runOnIdle {phoneState.value=phoneState.value.copy(progress=null);hostState.value=hostState.value.copy(busy=true)}
        compose.onNode(progress).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithText(l.t("mobile.voices.pick_file")).assertIsNotEnabled()
        compose.onNodeWithText(l.t("mobile.voices.record")).assertIsNotEnabled()
        compose.runOnIdle {hostState.value=hostState.value.copy(busy=false)}
        compose.onNode(progress).assertDoesNotExist()
        compose.onNodeWithText(l.t("mobile.voices.pick_file")).assertIsEnabled()
        compose.onNodeWithText(l.t("mobile.voices.record")).assertIsEnabled()
        compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        verify(phone,atLeastOnce()).cancelInference()
        compose.onNode(progress).assertDoesNotExist()
        compose.onNodeWithText("Voice name").assertDoesNotExist()
    }

    @Test fun phoneVoiceCanBeRenamedDeletedAndSelectedWithoutComputer()=runBlocking {
        val app=ApplicationProvider.getApplicationContext<Application>()
        val id=UUID.randomUUID().toString()
        val directory=File(app.noBackupFilesDir,"voices/$id").apply {mkdirs()}
        writePcm(LocalAudio(FloatArray(12000) {.1f},24000),File(directory,"reference.pcm"))
        File(directory,"voice.json").writeText(Json.encodeToString(PhoneVoice.serializer(),PhoneVoice(id,"Offline voice","A reference transcript",1,1)))
        val store=SessionStore(app,object: CredentialVault {override fun read(host: String): Secret?=null;override fun write(host: String,secret: Secret?) {}})
        store.update {Preferences(consent=false)}
        val vm=StudioViewModel(app,store)
        fun await(predicate: ()->Boolean) {compose.waitUntil(15000) {shadowOf(Looper.getMainLooper()).idle();predicate()}}
        try {
            compose.setContent {StudioApp(vm)}
            await {!vm.phone.state.value.loading&&vm.phone.state.value.voices.any {it.id==id}}
            compose.onNode(hasText("Voices") and hasClickAction()).performClick()
            compose.onNodeWithText("Offline voice").assertExists()
            compose.onNodeWithText("Phone").assertExists()
            compose.onNodeWithContentDescription("More actions for Offline voice").performScrollTo().performClick()
            compose.onNodeWithText("Rename").performClick()
            compose.onNodeWithText("Voice name").performTextClearance()
            compose.onNodeWithText("Voice name").performTextInput("New name")
            compose.onNodeWithText("Save").performClick()
            await {vm.phone.state.value.voices.any {it.name=="New name"}}
            compose.onNode(hasText("Home") and hasClickAction()).performClick()
            compose.onNodeWithTag("home.speak").performScrollTo().performClick()
            compose.onNodeWithContentDescription(com.voicestudio.mobile.l10n.L10n.load(ApplicationProvider.getApplicationContext<Application>(),"en").let {l ->
                l.t("mobile.android.selection",mapOf("label" to l.t("mobile.speak.voice"),"value" to l.t("mobile.local.preset_voice",mapOf("number" to 1))))}).performScrollTo().performClick()
            compose.onNodeWithText("Search").performTextInput("New name")
            compose.onNode(hasText("New name") and !hasSetTextAction()).performClick()
            compose.onNodeWithText("New name").assertExists()
            compose.onNode(hasText("Home") and hasClickAction()).performClick()
            compose.onNodeWithText("New name").performScrollTo().performClick()
            compose.onNodeWithText("New name").assertExists()
            // The preference is persisted asynchronously; wait for it instead of racing the IO dispatcher.
            await {vm.preferences.value.runLocations["speak"]=="phone"}
            org.junit.Assert.assertEquals("phone",vm.preferences.value.runLocations["speak"])
            compose.onNode(hasText("Voices") and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("More actions for New name").performScrollTo().performClick()
            compose.onNodeWithText("Delete").performClick()
            compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
            await {vm.phone.state.value.voices.none {it.id==id}}
        } finally {vm.viewModelScope.cancel();directory.deleteRecursively()}
    }
}
