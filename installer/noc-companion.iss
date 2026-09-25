; Instalador do Noc Companion (Inno Setup 6).
; Compilar: ISCC.exe installer\noc-companion.iss   (antes: dotnet publish → dist\companion\Noc.exe)

#define AppName "Noc Companion"
#define AppVersion "1.0.0"
#define AppExe "Noc.exe"
#define FirewallRule "Noc Companion"

[Setup]
AppId={{4F110E11-9BAB-42B6-AFBE-876E1A2C83B4}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher=Noc
AppPublisherURL=https://github.com/alanaraujo-bit/noc
AppSupportURL=https://github.com/alanaraujo-bit/noc
DefaultDirName={autopf}\Noc
DefaultGroupName=Noc
DisableProgramGroupPage=yes
OutputDir=..\dist
OutputBaseFilename=Noc-Companion-Setup-{#AppVersion}
SetupIconFile=..\companion\src\Noc.Companion\Assets\noc.ico
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
WizardStyle=modern
WizardSizePercent=110
WizardImageFile=wizard-164.bmp,wizard-328.bmp
WizardSmallImageFile=small-55.bmp,small-110.bmp
WizardImageStretch=no
Compression=lzma2/ultra64
SolidCompression=yes
; admin: regra de firewall para a conexão direta na rede local
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline dialog
UsedUserAreasWarning=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
CloseApplications=yes
RestartApplications=no
AppMutex=Noc.Companion.SingleInstance
VersionInfoVersion={#AppVersion}
VersionInfoDescription=Instalador do Noc Companion
ShowLanguageDialog=no

[Languages]
Name: "ptbr"; MessagesFile: "compiler:Languages\BrazilianPortuguese.isl"

[Messages]
ptbr.WelcomeLabel1=Seu PC como servidor pessoal de IA
ptbr.WelcomeLabel2=O Noc Companion conecta o LM Studio deste computador ao app Noc no seu Android — em casa ou fora dela, com criptografia de ponta a ponta.%n%nA inteligência continua rodando aqui. Suas conversas continuam suas.
ptbr.FinishedHeadingLabel=Pronto. Seu PC já está esperando o celular.
ptbr.FinishedLabel=Abra o Noc Companion, toque em “Parear celular” e leia o QR Code com o app Noc no Android.%n%nDepois disso é só deixar o PC ligado — o Noc inicia sozinho com o Windows.
ptbr.ClickFinish=Clique em Concluir para sair.

[CustomMessages]
ptbr.TaskAutostart=Iniciar o Noc junto com o Windows (recomendado)
ptbr.TaskDesktop=Criar atalho na Área de Trabalho
ptbr.TaskGroupStart=Comportamento:
ptbr.RunApp=Abrir o Noc Companion agora
ptbr.RemoveData=Remover também os dados do Noc neste PC?%n%nIsso apaga a identidade do PC, os celulares pareados e o histórico de segurança. Os celulares precisarão ser pareados de novo.%n%nEscolha “Não” para manter tudo e reinstalar depois sem perder nada.

[Tasks]
Name: "autostart"; Description: "{cm:TaskAutostart}"; GroupDescription: "{cm:TaskGroupStart}"
Name: "desktopicon"; Description: "{cm:TaskDesktop}"; GroupDescription: "{cm:TaskGroupStart}"; Flags: unchecked

[Files]
Source: "..\dist\companion\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{autoprograms}\Noc Companion"; Filename: "{app}\{#AppExe}"; Comment: "Acesso seguro às suas IAs locais pelo celular"
Name: "{autodesktop}\Noc Companion"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon

[Registry]
; início automático do usuário que instalou (o próprio app mantém isso atualizado)
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Noc Companion"; \
  ValueData: """{app}\{#AppExe}"" --minimized"; Tasks: autostart; Flags: uninsdeletevalue
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueName: "Noc Companion"; \
  Flags: deletevalue dontcreatekey uninsdeletevalue; Tasks: not autostart

[Run]
; Conexão direta na mesma Wi-Fi: libera só dispositivos da sub-rede local, em redes privadas e públicas
; (o Windows 11 marca muitas redes domésticas como "Pública"). O conteúdo é cifrado ponta a ponta de qualquer forma.
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#FirewallRule}"""; Flags: runhidden waituntilterminated
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""{#FirewallRule}"" dir=in action=allow program=""{app}\{#AppExe}"" enable=yes profile=private,public remoteip=localsubnet protocol=TCP"; Flags: runhidden waituntilterminated
Filename: "{app}\{#AppExe}"; Description: "{cm:RunApp}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM {#AppExe}"; Flags: runhidden; RunOnceId: "KillNoc"
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""{#FirewallRule}"""; Flags: runhidden; RunOnceId: "DelFirewall"

[Code]
procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataDir: String;
begin
  if CurUninstallStep = usPostUninstall then
  begin
    DataDir := ExpandConstant('{localappdata}\Noc');
    if DirExists(DataDir) and (not UninstallSilent) then
      if MsgBox(CustomMessage('RemoveData'), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES then
        DelTree(DataDir, True, True, True);
  end;
end;
