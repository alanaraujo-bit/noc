# Automação da janela do Companion via UI Automation.
#   uia.ps1 -Invoke "Parear celular"     aciona um botão/radio pelo nome (ou o índice -Index)
#   uia.ps1 -Texts                        lista os textos visíveis
param([string]$Invoke, [int]$Index = -1, [switch]$Texts, [switch]$Buttons)
Add-Type -AssemblyName UIAutomationClient, UIAutomationTypes
$A = [System.Windows.Automation.AutomationElement]
$p = Get-Process Noc -ErrorAction Stop | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
$root = $A::FromHandle($p.MainWindowHandle)
$all = $root.FindAll([System.Windows.Automation.TreeScope]::Descendants, [System.Windows.Automation.Condition]::TrueCondition)
if ($Texts) {
    foreach ($e in $all) {
        if ($e.Current.ControlType -eq [System.Windows.Automation.ControlType]::Text -and $e.Current.Name -and -not $e.Current.IsOffscreen) { $e.Current.Name }
    }
    return
}
if ($Buttons) {
    $i = 0
    foreach ($e in $all) {
        $ct = $e.Current.ControlType
        if ($ct -eq [System.Windows.Automation.ControlType]::Button -or $ct -eq [System.Windows.Automation.ControlType]::RadioButton) {
            "{0}: [{1}] {2}" -f $i, $ct.ProgrammaticName, $e.Current.Name
        }
        $i++
    }
    return
}
$target = $null
if ($Index -ge 0) { $target = $all[$Index] }
else {
    foreach ($e in $all) {
        $name = $e.Current.Name
        if (-not $name) {
            # botões com conteúdo composto: usa o texto do primeiro filho
            $child = $e.FindFirst([System.Windows.Automation.TreeScope]::Descendants, (New-Object System.Windows.Automation.PropertyCondition($A::NameProperty, $Invoke)))
            if ($child -and ($e.Current.ControlType -eq [System.Windows.Automation.ControlType]::Button)) { $target = $e; break }
        }
        elseif ($name -eq $Invoke -and ($e.Current.ControlType -eq [System.Windows.Automation.ControlType]::Button -or $e.Current.ControlType -eq [System.Windows.Automation.ControlType]::RadioButton)) { $target = $e; break }
    }
}
if (-not $target) { throw "não encontrei '$Invoke'" }
try { $target.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern).Invoke() }
catch { $target.GetCurrentPattern([System.Windows.Automation.SelectionItemPattern]::Pattern).Select() }
"ok: $Invoke"
