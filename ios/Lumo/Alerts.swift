import AVFoundation
import UIKit
import UserNotifications

/// Alarme de desconexão (como o `DisconnectAlert.java`): três bipes altos que tocam
/// mesmo com o iPhone no silencioso, mais uma notificação se o app estiver em segundo plano.
enum Alerts {
    private static var player: AVAudioPlayer?

    static func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func disconnect(_ message: String) {
        playAlarm()
        notify(title: "LUMO · câmera desconectada", body: message)
    }

    static func notify(title: String, body: String) {
        guard UIApplication.shared.applicationState != .active else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "lumo-\(title)", content: content, trigger: nil))
    }

    static func playAlarm() {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            player = try AVAudioPlayer(data: beeps())
            player?.volume = 1
            player?.play()
        } catch {
            AudioServicesPlayAlertSound(SystemSoundID(1005))
        }
    }

    /// WAV mono 16 bits: 3 bipes de 880 Hz (350 ms) com 200 ms de intervalo.
    static func beeps() -> Data {
        let rate = 44_100.0
        var samples: [Int16] = []
        for _ in 0..<3 {
            let n = Int(rate * 0.35)
            for i in 0..<n {
                let fade = min(1, Double(min(i, n - i)) / 400)
                samples.append(Int16(sin(2 * .pi * 880 * Double(i) / rate) * 30_000 * fade))
            }
            samples.append(contentsOf: repeatElement(0, count: Int(rate * 0.2)))
        }
        var d = Data()
        func put32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func put16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        let bytes = UInt32(samples.count * 2)
        d.append(contentsOf: Array("RIFF".utf8)); put32(36 + bytes)
        d.append(contentsOf: Array("WAVEfmt ".utf8)); put32(16); put16(1); put16(1)
        put32(UInt32(rate)); put32(UInt32(rate) * 2); put16(2); put16(16)
        d.append(contentsOf: Array("data".utf8)); put32(bytes)
        for s in samples { put16(UInt16(bitPattern: s)) }
        return d
    }
}
