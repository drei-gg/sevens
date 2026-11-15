import SwiftUI
import UIKit
import QRUI

/// The share extension's principal class: hosts `CheckExtensionRoot` from QRUI.
final class ShareViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        let host = UIHostingController(rootView: CheckExtensionRoot(context: extensionContext))
        addChild(host)
        host.view.frame = view.bounds
        host.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(host.view)
        host.didMove(toParent: self)
    }
}
