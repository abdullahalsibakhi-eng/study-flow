import Foundation
import Security
// A narrow stdin-only credential writer; secrets never appear in process arguments or logs.
let data = FileHandle.standardInput.readDataToEndOfFile()
guard data.count <= 100_000, (try? JSONSerialization.jsonObject(with: data)) != nil else { exit(1) }
let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.studyflow.credentials", kSecAttrAccount as String: "brightspace-oauth"]
let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
if status == errSecItemNotFound {
    var item = query
    item[kSecValueData as String] = data
    item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    exit(SecItemAdd(item as CFDictionary, nil) == errSecSuccess ? 0 : 1)
}
exit(status == errSecSuccess ? 0 : 1)
