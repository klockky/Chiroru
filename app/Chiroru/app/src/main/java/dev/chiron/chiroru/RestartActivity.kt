package dev.chiron.chiroru

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

/** A fresh zygote process relaunches the UI after the bootstrap process exits. */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = intent.getIntExtra("previous_pid", -1)
        val deadline = SystemClock.uptimeMillis() + 3000
        val handler = Handler(Looper.getMainLooper())
        fun launchWhenExited() {
            val alive = if (previous <= 0) false else try {
                Os.kill(previous, 0); true
            } catch (error: ErrnoException) { error.errno != OsConstants.ESRCH }
            if (alive && SystemClock.uptimeMillis() < deadline) {
                handler.postDelayed({ launchWhenExited() }, 50)
            } else {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("open_console", true))
                finish()
                Process.killProcess(Process.myPid())
            }
        }
        launchWhenExited()
    }

    companion object {
        internal fun restart(context: Context) {
            context.startActivity(Intent(context, RestartActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra("previous_pid", Process.myPid()))
            // A credential-modified process must terminate itself: the fresh app
            // UID cannot signal its UID-0 predecessor.
            Process.killProcess(Process.myPid())
        }
    }
}
