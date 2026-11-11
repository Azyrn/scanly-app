package com.skeler.scanely.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.json.JSONObject

/**
 * The journeys the profile and the benchmark share, so both cover the same code: cold start to
 * Home, and Home → AI options sheet → Build Documents → the chat sliding in → drawer → typing.
 *
 * Text matches the English UI, so the phone must run in English. Nothing is ever sent: typing
 * stops before Send, so no assistant turn runs and nothing is billed.
 */
internal const val PACKAGE = "com.skeler.scanely"

private const val WAIT_MS = 10_000L

/** Where the runner leaves the test account for this run; pushed before, deleted after. */
private const val CREDENTIALS = "/data/local/tmp/scanly-bp.json"

internal fun MacrobenchmarkScope.waitForHome() {
    check(device.wait(Until.hasObject(By.text("AI Scan")), WAIT_MS)) { "Home did not appear" }
}

/** Signs the test account in when the app is signed out (Gradle reinstalls the app per run). */
internal fun MacrobenchmarkScope.signInIfNeeded() {
    waitForHome()
    device.findObject(By.text("AI Scan")).click()
    val signIn = device.wait(Until.findObject(By.text("Sign in or create account")), 3_000)
    if (signIn == null) {
        device.pressBack()
        device.waitForIdle()
        return
    }
    val account = JSONObject(device.executeShellCommand("cat $CREDENTIALS").trim().ifEmpty {
        error("No test account at $CREDENTIALS: push it before running")
    })
    signIn.click()
    check(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS)) { "No sign-in form" }
    val fields = device.findObjects(By.clazz("android.widget.EditText"))
    fields[0].text = account.getString("email")
    fields[1].text = account.getString("password")
    // Text is set without opening the keyboard, so there is none to dismiss: Back would leave the form.
    device.wait(Until.findObject(By.text("Sign in")), WAIT_MS).click()
    waitForHome()
    check(device.wait(Until.hasObject(By.textContains("credits")), WAIT_MS)) { "Sign-in did not finish" }
}

/** Home → AI options sheet → Build Documents → chat; then the drawer, then the message box. */
internal fun MacrobenchmarkScope.buildDocumentsJourney() {
    waitForHome()
    device.findObject(By.text("AI Scan")).click()
    device.wait(Until.findObject(By.text("Build Documents with AI")), WAIT_MS).click()
    check(device.wait(Until.hasObject(By.text("Describe a document…")), WAIT_MS)) { "The chat did not open" }
    device.waitForIdle()

    device.findObject(By.desc("Chats and plan")).click()
    check(device.wait(Until.hasObject(By.text("Search chats")), WAIT_MS)) { "The drawer did not open" }
    device.waitForIdle()
    device.pressBack()
    check(device.wait(Until.gone(By.text("Search chats")), WAIT_MS)) { "Back did not close the drawer" }
    device.waitForIdle()

    device.findObject(By.text("Describe a document…")).click()
    device.wait(Until.findObject(By.clazz("android.widget.EditText")), WAIT_MS).text = "An invoice for Nour Cafe"
    device.waitForIdle()
    device.pressBack()
    device.waitForIdle()
}
