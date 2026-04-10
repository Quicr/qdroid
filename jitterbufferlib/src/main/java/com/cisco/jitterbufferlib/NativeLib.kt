package com.cisco.jitterbufferlib

class NativeLib {

    /**
     * A native method that is implemented by the 'jitterbufferlib' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    companion object {
        // Used to load the 'jitterbufferlib' library on application startup.
        init {
            System.loadLibrary("jitterbufferlib")
        }
    }
}