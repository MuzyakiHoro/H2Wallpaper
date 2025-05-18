// Constants.kt or similar // 你把它放在了 Constants.kt 中，很好！
object FocusParams {
    // 用于从 MainActivity 启动 FocusActivity 时传递数据的 Key
    const val EXTRA_IMAGE_URI = "com.example.h2wallpaper.EXTRA_IMAGE_URI"
    const val EXTRA_ASPECT_RATIO = "com.example.h2wallpaper.EXTRA_ASPECT_RATIO"
    const val EXTRA_INITIAL_FOCUS_X = "com.example.h2wallpaper.EXTRA_INITIAL_FOCUS_X" // 初始焦点X (0.0 to 1.0)
    const val EXTRA_INITIAL_FOCUS_Y = "com.example.h2wallpaper.EXTRA_INITIAL_FOCUS_Y" // 初始焦点Y (0.0 to 1.0)

    // 用于从 FocusActivity 返回结果给 MainActivity 时，在 Intent 中携带数据的 Key
    const val RESULT_FOCUS_X = "com.example.h2wallpaper.RESULT_FOCUS_X" // 返回的焦点X (0.0 to 1.0)
    const val RESULT_FOCUS_Y = "com.example.h2wallpaper.RESULT_FOCUS_Y" // 返回的焦点Y (0.0 to 1.0)

    // 你还保留了这个，虽然 ActivityResultLauncher 后不直接用，但定义了也没问题
    const val REQUEST_CODE_FOCUS_SELECTION = 1001
}