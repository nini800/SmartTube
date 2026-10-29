' Starts pinou-sync.ps1 without a console window (action of the "PinouTube auto-update" scheduled task).
' Window style 0: hidden, so the night run never shows a window or takes the focus.
Dim shell, script
Set shell = CreateObject("WScript.Shell")
script = Replace(WScript.ScriptFullName, WScript.ScriptName, "pinou-sync.ps1")
WScript.Quit shell.Run("powershell.exe -NoProfile -ExecutionPolicy Bypass -File """ & script & """", 0, True)
