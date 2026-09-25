using System.Windows;
using System.Windows.Input;
using Noc.Companion.ViewModels;

namespace Noc.Companion;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void Scrim_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (DataContext is MainViewModel vm) vm.ClosePairing.Execute(null);
    }
}
