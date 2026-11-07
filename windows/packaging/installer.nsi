Unicode true
!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "FileFunc.nsh"
!ifndef APP_VERSION
  !define APP_VERSION "0.10.0"
!endif
!ifndef PACKAGE_DIR
  !define PACKAGE_DIR "..\dist\MusicVault-Windows-x64-${APP_VERSION}"
!endif
!ifndef OUTPUT_FILE
  !define OUTPUT_FILE "..\dist\MusicVault-Windows-x64-${APP_VERSION}-Setup.exe"
!endif
Name "MusicVault ${APP_VERSION}"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\MusicVault"
RequestExecutionLevel user
SetCompressor /SOLID zlib
!define MUI_ICON "musicvault.ico"
!define MUI_UNICON "musicvault.ico"
!define MUI_FINISHPAGE_RUN "$INSTDIR\MusicVault.exe"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Spanish"
!insertmacro MUI_LANGUAGE "English"
Var NoIntegration
Function .onInit
  StrCpy $NoIntegration 0
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/NOINTEGRATION" $1
  ${IfNot} ${Errors}
    StrCpy $NoIntegration 1
  ${EndIf}
FunctionEnd
Section "MusicVault"
  SetShellVarContext current
  InitPluginsDir
  SetOutPath "$PLUGINSDIR"
  File "installation_guard.py"
  check_running:
  ${If} ${FileExists} "$INSTDIR\python\python.exe"
    nsExec::ExecToStack '$\"$INSTDIR\python\python.exe$\" $\"$PLUGINSDIR\installation_guard.py$\" $\"$INSTDIR$\"'
    Pop $0
    Pop $1
    ${If} $0 != 0
      IfSilent installation_busy
      MessageBox MB_RETRYCANCEL|MB_ICONINFORMATION "Cierra MusicVault desde Salir y pulsa Reintentar. Tu biblioteca se conservara." IDRETRY check_running
      installation_busy:
      SetErrorLevel 2
      Abort
    ${EndIf}
  ${EndIf}
  SetOutPath "$INSTDIR"
  File "${PACKAGE_DIR}\MusicVault.exe"
  File "${PACKAGE_DIR}\MusicVault.cmd"
  File "${PACKAGE_DIR}\Diagnostico.cmd"
  File "${PACKAGE_DIR}\launch.py"
  File "${PACKAGE_DIR}\README.md"
  File "${PACKAGE_DIR}\UPDATES.md"
  File "${PACKAGE_DIR}\RELEASE_0.10.0.md"
  File "${PACKAGE_DIR}\VERIFICATION.md"
  File "${PACKAGE_DIR}\THIRD_PARTY_NOTICES.md"
  File "${PACKAGE_DIR}\dependencies.lock.json"
  File /r /x __pycache__ /x *.pyc "${PACKAGE_DIR}\python"
  File /r "${PACKAGE_DIR}\runtime"
  File /r /x __pycache__ /x *.pyc "${PACKAGE_DIR}\musicvault"
  File /r "${PACKAGE_DIR}\licenses"
  ${If} $NoIntegration == 0
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\MusicVault"
  CreateShortcut "$SMPROGRAMS\MusicVault\MusicVault.lnk" "$INSTDIR\MusicVault.exe"
  CreateShortcut "$SMPROGRAMS\MusicVault\Desinstalar.lnk" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "DisplayName" "MusicVault"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "DisplayIcon" "$INSTDIR\MusicVault.exe"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "NoRepair" 1
  ${EndIf}
SectionEnd
Section "Uninstall"
  SetShellVarContext current
  ; Keep the user's library, preferences and downloads in Data.
  RMDir /r "$INSTDIR\python"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\musicvault"
  RMDir /r "$INSTDIR\licenses"
  Delete "$INSTDIR\MusicVault.exe"
  Delete "$INSTDIR\MusicVault.cmd"
  Delete "$INSTDIR\Diagnostico.cmd"
  Delete "$INSTDIR\launch.py"
  Delete "$INSTDIR\README.md"
  Delete "$INSTDIR\UPDATES.md"
  Delete "$INSTDIR\RELEASE_0.10.0.md"
  Delete "$INSTDIR\VERIFICATION.md"
  Delete "$INSTDIR\THIRD_PARTY_NOTICES.md"
  Delete "$INSTDIR\dependencies.lock.json"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  RMDir /r "$SMPROGRAMS\MusicVault"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault"
SectionEnd
