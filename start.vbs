' start.vbs - launch launcher.ps1 in hidden window (customer entry point, ADR-0016)
Set sh = CreateObject("WScript.Shell")
sh.CurrentDirectory = CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName)
sh.Run "powershell.exe -NoProfile -ExecutionPolicy Bypass -File ""launcher.ps1""", 0, False