package org.openvm.app

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
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
}

