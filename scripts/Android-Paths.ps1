function Get-AndroidSdkRoot {
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    if ($env:LOCALAPPDATA) { return Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    throw 'Set ANDROID_HOME to your Android SDK directory.'
}

function Get-AndroidJdkRoot {
    if ($env:JAVA_HOME) { return $env:JAVA_HOME }
    if ($env:ProgramFiles) { return Join-Path $env:ProgramFiles 'Android\Android Studio\jbr' }
    throw 'Set JAVA_HOME to your JDK directory.'
}
