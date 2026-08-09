package org.openvm.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun emptyStateExplainsHowToStart() {
        onView(withText("No virtual machines yet")).check { view, noMatchException ->
            if (noMatchException != null) throw noMatchException
            check(view.isShown) { "The empty state must be visible on a fresh profile store" }
        }
    }

    @Test
    fun profileEditorExposesBackendAndQemuExecutableImport() {
        onView(withText("Create VM profile")).perform(click())
        onView(withText("Android Virtualization Framework")).check(matches(isDisplayed()))
        onView(withText("Import QEMU executable")).perform(scrollTo()).check(matches(isDisplayed()))
    }
}
