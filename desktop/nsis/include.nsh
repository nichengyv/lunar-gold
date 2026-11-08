; A running executable cannot be deleted on Windows. If the target executable
; does not exist, this installation cannot have a running app to close.
; electron-builder otherwise runs its process detection even for an empty
; first-install directory, which can produce a false "app cannot be closed".
!include "getProcessInfo.nsh"
Var pid
!ifdef BUILD_UNINSTALLER
  Var originalUninstallDir
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

!macro customCheckAppRunning
  ${If} ${FileExists} "$INSTDIR\${APP_EXECUTABLE_FILENAME}"
    !insertmacro IS_POWERSHELL_AVAILABLE
    !insertmacro _CHECK_APP_RUNNING
  ${EndIf}
!macroend

; The running uninstaller is copied to TEMP. Restore the original directory
; from the special launch parameter; use the registry only as a fallback.
!macro customUnInit
  !insertmacro GetOriginalUninstallDir $originalUninstallDir
  ${If} $originalUninstallDir != ""
    StrCpy $INSTDIR $originalUninstallDir
  ${Else}
    SetRegView 64
    ReadRegStr $0 HKCU "${INSTALL_REGISTRY_KEY}" InstallLocation
    ${If} $0 != ""
      StrCpy $INSTDIR $0
      StrCpy $originalUninstallDir $0
    ${EndIf}
  ${EndIf}
!macroend

; The generic mode page can reset INSTDIR again, so restore it before removal.
!macro customUnInstall
  ${If} $originalUninstallDir != ""
    StrCpy $INSTDIR $originalUninstallDir
  ${EndIf}
!macroend

; Bypass electron-builder's update-only atomic rename/restore path. A normal
; uninstall must remove the registered installation directory, not restore files
; into it after a failed rename.
!macro customRemoveFiles
  SetOutPath $TEMP
  RMDir /r $INSTDIR
!macroend
