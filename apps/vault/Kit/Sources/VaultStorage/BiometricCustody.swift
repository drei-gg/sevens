import Foundation

/// Which Face ID / Touch ID Keychain items to delete, decided apart from the Keychain so it can be
/// tested. Biometric unlock is for one vault at a time: the app stores that vault's ID (the item's
/// account) and nothing else may hold a vault's key. Items are named by vault UUID strings.
public enum BiometricCustody {
    /// Before enrolling `vault`: every earlier item, so a previous vault's key never lingers.
    public static func enrolling(_ vault: String, stored: String?, items: [String]) -> Set<String> {
        var remove = Set(items); if let stored { remove.insert(stored) }
        remove.remove(vault)   // replaced by the new item in the same step
        return remove
    }
    /// Turning biometric unlock off: the stored vault's item (whichever vault is open, if any) and
    /// any stray item.
    public static func removing(stored: String?, items: [String]) -> Set<String> {
        var remove = Set(items); if let stored { remove.insert(stored) }
        return remove
    }
    /// At launch: whether the stored ID still names a vault on this device, and the items to delete
    /// (every item that is not the stored vault's, plus the stored one when its vault is gone).
    public static func cleanup(stored: String?, vaults: Set<String>, items: [String]) -> (keep: Bool, remove: Set<String>) {
        let keep = stored.map { vaults.contains($0) } ?? false
        var remove = Set(items)
        if keep, let stored { remove.remove(stored) } else if let stored { remove.insert(stored) }
        return (keep, remove)
    }
}
