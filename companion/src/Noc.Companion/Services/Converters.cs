using System.Globalization;
using System.Windows;
using System.Windows.Data;

namespace Noc.Companion.Services;

/// <summary>value == parameter → true (e ConvertBack devolve o parâmetro, para RadioButton).</summary>
public sealed class EqualsConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var eq = string.Equals(value?.ToString(), parameter?.ToString(), StringComparison.Ordinal);
        return targetType == typeof(Visibility) ? (eq ? Visibility.Visible : Visibility.Collapsed) : eq;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        value is true ? parameter?.ToString() ?? "" : Binding.DoNothing;
}

public sealed class NotConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture)
    {
        var b = value is true;
        return targetType == typeof(Visibility) ? (b ? Visibility.Collapsed : Visibility.Visible) : !b;
    }

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => value is not true;
}

/// <summary>string vazia/nula → Collapsed.</summary>
public sealed class TextVisConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        string.IsNullOrWhiteSpace(value as string) ? Visibility.Collapsed : Visibility.Visible;

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>Fração 0..1 → largura proporcional (parâmetro = largura total).</summary>
public sealed class FractionConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        (value is double d ? Math.Clamp(d, 0, 1) : 0) * double.Parse(parameter?.ToString() ?? "100", CultureInfo.InvariantCulture);

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => throw new NotSupportedException();
}

/// <summary>Nível de log → pincel do tema.</summary>
public sealed class LevelBrushConverter : IValueConverter
{
    public object Convert(object? value, Type targetType, object? parameter, CultureInfo culture) =>
        Application.Current.Resources[(value as string) switch { "alert" => "Err", "warn" => "Warn", _ => "Text3" }];

    public object ConvertBack(object? value, Type targetType, object? parameter, CultureInfo culture) => throw new NotSupportedException();
}
