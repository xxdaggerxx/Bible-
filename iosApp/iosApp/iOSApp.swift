import SwiftUI
import Shared

/// Ink & Word on iPad and iPhone: the shared app (Kotlin, Compose Multiplatform) in one window.
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            // Edge to edge, as on Android: the app keeps clear of the status bar and home indicator itself.
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
