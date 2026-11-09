; electron-builder's PowerShell check treats every process below INSTDIR as
; the app. Use the exact executable name instead so an installer, uninstaller,
; or unrelated helper cannot trigger a false "app cannot be closed" error.
!include "nsProcess.nsh"
!ifdef BUILD_UNINSTALLER
  Var originalUninstallDir
  Var isUpdateFlow
!endif

; NSIS removes the special _?= parameter from $CMDLINE before macros can read
; it. StdUtils still exposes the real command line; recover the original path
; before electron-builder's multi-user initialization replaces it.
!macro GetOriginalUninstallDir outVar
  Push $R8
  Push $R9
  Push $R7
  Push $R6
  Push $R5

  ${StdUtils.GetAllParameters} $R8 "0"
  StrCpy $R9 ""
  StrLen $R7 $R8
  IntOp $R7 $R7 - 2
  StrCpy $R6 0

  ${Do}
    StrCpy $R5 $R8 3 $R6
    ${If} $R5 == "_?="
      IntOp $R6 $R6 + 3
      StrCpy $R9 $R8 "" $R6
      ${Break}
    ${EndIf}
    IntOp $R6 $R6 + 1
  ${LoopUntil} $R6 > $R7

  StrCpy ${outVar} $R9
  Pop $R5
  Pop $R6
  Pop $R7
  Pop $R9
  Pop $R8
!macroend

; This package is always per-user. electron-builder otherwise shows its generic
; per-user/per-machine page in both the installer and uninstaller, using install
; wording during uninstall and losing the custom directory when registry state
; is unavailable. Force the declared install mode before that page is created.
!macro customInstallMode
  StrCpy $isForceMachineInstall "0"
  StrCpy $isForceCurrentInstall "1"
!macroend

; Close a running instance before replacing files, but never block the
; installation. If the close/kill reports failure we continue: the file copy
; step already retries and offers its own retry prompt, and a dead-end dialog
; made the installer unusable when the process check produced a false positive.
!macro customCheckAppRunning
  ${If} ${FileExists} "$INSTDIR\${APP_EXECUTABLE_FILENAME}"
    ${nsProcess::FindProcess} "${APP_EXECUTABLE_FILENAME}" $R0
    ${If} $R0 == 0
      DetailPrint "$(appClosing)"
      ${nsProcess::CloseProcess} "${APP_EXECUTABLE_FILENAME}" $R0
      Sleep 1000
      ${nsProcess::FindProcess} "${APP_EXECUTABLE_FILENAME}" $R0
      ${If} $R0 == 0
        ${nsProcess::KillProcess} "${APP_EXECUTABLE_FILENAME}" $R0
        Sleep 1000
      ${EndIf}
    ${EndIf}
  ${EndIf}
!macroend

; The running uninstaller is copied to TEMP. Restore the original directory
; from the special launch parameter; use the registry only as a fallback.
!macro customUnInit
  !insertmacro GetOriginalUninstallDir $originalUninstallDir
  ${If} $originalUninstallDir != ""
    StrCpy $isUpdateFlow "1"
    StrCpy $INSTDIR $originalUninstallDir
  ${Else}
    StrCpy $isUpdateFlow "0"
    SetRegView 64
    ReadRegStr $0 HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
    ${If} $0 != ""
      StrCpy $INSTDIR $0
      StrCpy $originalUninstallDir $0
    ${EndIf}
  ${EndIf}
!macroend

; The generic mode page can reset INSTDIR again, so restore it before removal.
; For a real uninstall (not an update run via _?=), also remove the current-user
; shortcuts and registry keys explicitly: electron-builder's later cleanup runs
; after the program files are gone and can be skipped, leaving both behind.
!macro customUnInstall
  ${If} $originalUninstallDir != ""
    StrCpy $INSTDIR $originalUninstallDir
  ${EndIf}
  ${If} $isUpdateFlow == "0"
    SetShellVarContext current
    Delete "$DESKTOP\${SHORTCUT_NAME}.lnk"
    Delete "$SMPROGRAMS\${SHORTCUT_NAME}.lnk"
    DeleteRegKey HKCU "${UNINSTALL_REGISTRY_KEY}"
    DeleteRegKey HKCU "${INSTALL_REGISTRY_KEY}"
  ${EndIf}
!macroend

; Bypass electron-builder's update-only atomic rename/restore path. A normal
; uninstall must remove the registered installation directory, not restore files
; into it after a failed rename.
!macro customRemoveFiles
  SetOutPath $TEMP
  RMDir /r $INSTDIR
!macroend
