' QQ音乐代理隐藏启动器（供计划任务/开机自启调用）
' 按本脚本所在目录定位 qqProxy.mjs，项目移动/克隆后无需修改路径。
' 阻塞等待 node 退出并返回其退出码：代理异常退出时计划任务判定失败并自动重启。
Dim sh, fso, dir, nodeExe, rc
Set sh = CreateObject("Wscript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
dir = fso.GetParentFolderName(WScript.ScriptFullName)

nodeExe = sh.ExpandEnvironmentStrings("%ProgramFiles%") & "\nodejs\node.exe"
If Not fso.FileExists(nodeExe) Then
    ' 常规安装路径不存在时回退到 PATH 查找
    nodeExe = "node"
End If

rc = sh.Run("""" & nodeExe & """ """ & dir & "\qqProxy.mjs""", 0, True)
WScript.Quit rc
