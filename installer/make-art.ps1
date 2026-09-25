# Arte do instalador (Inno Setup, estilo moderno): imagem lateral e ícone pequeno, em 100% e 200%.
Add-Type -AssemblyName System.Drawing
$here = $PSScriptRoot
$fonts = New-Object System.Drawing.Text.PrivateFontCollection
$fonts.AddFontFile((Join-Path $here '..\android\app\src\main\res\font\instrument_serif_regular.ttf'))
$fonts.AddFontFile((Join-Path $here '..\android\app\src\main\res\font\instrument_serif_italic.ttf'))
$fonts.AddFontFile((Join-Path $here '..\android\app\src\main\res\font\geist_regular.ttf'))
$serif = $fonts.Families | Where-Object { $_.Name -eq 'Instrument Serif' }
$geist = $fonts.Families | Where-Object { $_.Name -eq 'Geist' }

$bg = [System.Drawing.Color]::FromArgb(255, 20, 19, 17)
$ember = [System.Drawing.Color]::FromArgb(255, 240, 138, 75)
$ink = [System.Drawing.Color]::FromArgb(255, 238, 236, 232)
$muted = [System.Drawing.Color]::FromArgb(255, 150, 145, 137)

function Mark($g, [float]$cx, [float]$cy, [float]$r, $ringColor) {
    $dot = $r * 0.41
    $g.FillEllipse((New-Object System.Drawing.SolidBrush $ember), $cx - $dot, $cy - $dot, 2 * $dot, 2 * $dot)
    $pen = New-Object System.Drawing.Pen $ringColor, ([float]($r * 0.23))
    $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
    $g.DrawArc($pen, $cx - $r, $cy - $r, 2 * $r, 2 * $r, -36, 300)
}

function Side([int]$w, [int]$h, [string]$out) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'
    $g.Clear($bg)
    $s = $w / 164.0
    # anel grande, cortado pela borda: sensação de "sinal" saindo da máquina
    $big = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(40, 238, 236, 232)), ([float](2 * $s))
    $g.DrawEllipse($big, [float](-60 * $s), [float](40 * $s), [float](220 * $s), [float](220 * $s))
    Mark $g ([float](50 * $s)) ([float](150 * $s)) ([float](26 * $s)) $ink
    $f1 = New-Object System.Drawing.Font($serif, [float](40 * $s), [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawString('noc', $f1, (New-Object System.Drawing.SolidBrush $ink), [float](18 * $s), [float](206 * $s))
    $f2 = New-Object System.Drawing.Font($serif, [float](15 * $s), [System.Drawing.FontStyle]::Italic, [System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawString("Minha IA.`nMeu computador.`nMeus dados.", $f2, (New-Object System.Drawing.SolidBrush $muted), [float](20 * $s), [float](252 * $s))
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $g.Dispose(); $bmp.Dispose()
}

function Small([int]$w, [string]$out) {
    $bmp = New-Object System.Drawing.Bitmap $w, $w, ([System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::White)
    $r = $w * 0.30
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $rad = [int]($w * 0.22); $d = 2 * $rad; $e = $w - 1
    $path.AddArc(0, 0, $d, $d, 180, 90); $path.AddArc($e - $d, 0, $d, $d, 270, 90)
    $path.AddArc($e - $d, $e - $d, $d, $d, 0, 90); $path.AddArc(0, $e - $d, $d, $d, 90, 90); $path.CloseFigure()
    $g.FillPath((New-Object System.Drawing.SolidBrush $bg), $path)
    Mark $g ([float]($w / 2)) ([float]($w / 2)) ([float]$r) $ink
    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $g.Dispose(); $bmp.Dispose()
}

Side 164 314 (Join-Path $here 'wizard-164.bmp')
Side 328 628 (Join-Path $here 'wizard-328.bmp')
Small 55 (Join-Path $here 'small-55.bmp')
Small 110 (Join-Path $here 'small-110.bmp')
'arte gerada'
