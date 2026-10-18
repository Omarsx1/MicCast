import SwiftUI
import AppKit

@main
struct MicCastApp: App {
    @StateObject private var controlServer = ControlServer.shared
    @StateObject private var audioReceiver = AudioReceiver.shared
    @StateObject private var audioOutput = VirtualAudioOutput.shared

    init() {
        controlServer.start()
        audioReceiver.start()
        audioOutput.start()
    }

    var body: some Scene {
        MenuBarExtra("MicCast", systemImage: controlServer.isClientConnected ? "mic.fill" : "mic.slash") {
            MicCastMenuBarView()
                .environmentObject(controlServer)
                .environmentObject(audioReceiver)
                .environmentObject(audioOutput)
        }
        .menuBarExtraStyle(.window)
    }
}
