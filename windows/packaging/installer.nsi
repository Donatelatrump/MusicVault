Unicode true
!include "MUI2.nsh"
!ifndef PACKAGE_DIR
  !define PACKAGE_DIR "..\dist\MusicVault-Windows-x64-0.9.7"
!endif
!ifndef OUTPUT_FILE
  !define OUTPUT_FILE "..\dist\MusicVault-Windows-x64-0.9.7-Setup.exe"
!endif
Name "MusicVault 0.9.7"
OutFile "${OUTPUT_FILE}"
InstallDir "$LOCALAPPDATA\Programs\MusicVault"
RequestExecutionLevel user
SetCompressor /SOLID zlib
!define MUI_ICON "musicvault.ico"
!define MUI_UNICON "musicvault.ico"
!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "Spanish"
!insertmacro MUI_LANGUAGE "English"
Section "MusicVault"
  SetShellVarContext current
  SetOutPath "$INSTDIR"
  File "${PACKAGE_DIR}\MusicVault.exe"
  File "${PACKAGE_DIR}\MusicVault.cmd"
  File "${PACKAGE_DIR}\Diagnostico.cmd"
  File "${PACKAGE_DIR}\launch.py"
  File "${PACKAGE_DIR}\README.md"
  File "${PACKAGE_DIR}\VERIFICATION.md"
  File "${PACKAGE_DIR}\THIRD_PARTY_NOTICES.md"
  File "${PACKAGE_DIR}\dependencies.lock.json"
  File /r /x __pycache__ /x *.pyc "${PACKAGE_DIR}\python"
  File /r "${PACKAGE_DIR}\runtime"
  File /r /x __pycache__ /x *.pyc "${PACKAGE_DIR}\musicvault"
  File /r "${PACKAGE_DIR}\licenses"
  WriteUninstaller "$INSTDIR\Uninstall.exe"
  CreateDirectory "$SMPROGRAMS\MusicVault"
  CreateShortcut "$SMPROGRAMS\MusicVault\MusicVault.lnk" "$INSTDIR\MusicVault.exe"
  CreateShortcut "$SMPROGRAMS\MusicVault\Desinstalar.lnk" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "DisplayName" "MusicVault"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "DisplayVersion" "0.9.7"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "UninstallString" '$\"$INSTDIR\Uninstall.exe$\"'
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "DisplayIcon" "$INSTDIR\MusicVault.exe"
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "NoRepair" 1
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
  Delete "$INSTDIR\VERIFICATION.md"
  Delete "$INSTDIR\THIRD_PARTY_NOTICES.md"
  Delete "$INSTDIR\dependencies.lock.json"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  RMDir /r "$SMPROGRAMS\MusicVault"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault"
SectionEnd
