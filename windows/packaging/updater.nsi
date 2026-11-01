Unicode true
!include "FileFunc.nsh"
!include "LogicLib.nsh"
!ifndef PAYLOAD_DIR
  !define PAYLOAD_DIR "..\build\update"
!endif
!ifndef OUTPUT_FILE
  !define OUTPUT_FILE "..\dist\MusicVault-Windows-x64-0.9.7-Update1.exe"
!endif
Name "MusicVault - Actualizacion 1 para Windows 0.9.7"
OutFile "${OUTPUT_FILE}"
Icon "musicvault.ico"
RequestExecutionLevel user
InstallDir "$LOCALAPPDATA\Programs\MusicVault"
SetCompressor /SOLID zlib
AutoCloseWindow true
ShowInstDetails show
Var Result
Var Details
Page instfiles

Function .onInit
  ${GetParameters} $0
  ClearErrors
  ${GetOptions} $0 "/D=" $1
  ${IfNot} ${Errors}
    StrCpy $INSTDIR $1
  ${Else}
    ReadRegStr $1 HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\MusicVault" "InstallLocation"
    ${If} ${FileExists} "$1\MusicVault.exe"
      StrCpy $INSTDIR $1
    ${ElseIf} ${FileExists} "$EXEDIR\MusicVault.exe"
      StrCpy $INSTDIR $EXEDIR
    ${EndIf}
  ${EndIf}
  ${IfNot} ${FileExists} "$INSTDIR\python\python.exe"
    IfSilent missing
    nsDialogs::SelectFolderDialog "Selecciona la carpeta donde esta MusicVault.exe" "$INSTDIR"
    Pop $INSTDIR
  ${EndIf}
  ${IfNot} ${FileExists} "$INSTDIR\python\python.exe"
    missing:
    IfSilent +2
    MessageBox MB_ICONSTOP "No se encontro MusicVault instalado. Este archivo actualiza Windows 0.9.7; no descarga las dependencias."
    SetErrorLevel 1
    Abort
  ${EndIf}
FunctionEnd

Section
  InitPluginsDir
  SetOutPath "$PLUGINSDIR"
  File "${PAYLOAD_DIR}\payload.zip"
  File "${PAYLOAD_DIR}\manifest.json"
  File "apply_update.py"
  retry:
  nsExec::ExecToStack '$\"$INSTDIR\python\python.exe$\" $\"$PLUGINSDIR\apply_update.py$\" --root $\"$INSTDIR$\" --payload $\"$PLUGINSDIR\payload.zip$\" --manifest $\"$PLUGINSDIR\manifest.json$\"'
  Pop $Result
  Pop $Details
  DetailPrint "$Details"
  ${If} $Result == 2
    IfSilent failed
    MessageBox MB_RETRYCANCEL|MB_ICONINFORMATION "$Details" IDRETRY retry
    Goto failed
  ${ElseIf} $Result != 0
    IfSilent failed
    MessageBox MB_ICONSTOP "$Details"
    Goto failed
  ${EndIf}
  IfSilent done
  SetOutPath "$INSTDIR"
  Exec '$\"$INSTDIR\MusicVault.exe$\"'
  Goto done
  failed:
  SetErrorLevel 1
  SetAutoClose true
  done:
SectionEnd
