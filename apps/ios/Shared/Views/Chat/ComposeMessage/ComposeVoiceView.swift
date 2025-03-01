//
//  ComposeVoiceView.swift
//  SimpleX (iOS)
//
//  Created by JRoberts on 21.11.2022.
//  Copyright © 2022 SimpleX Chat. All rights reserved.
//

import SwiftUI
import SimpleXChat

enum VoiceMessagePlaybackState {
    case noPlayback
    case playing
    case paused
}

func voiceMessageTime(_ time: TimeInterval) -> String {
    let min = Int(time / 60)
    let sec = Int(time.truncatingRemainder(dividingBy: 60))
    return String(format: "%02d:%02d", min, sec)
}

func voiceMessageTime_(_ time: TimeInterval?) -> String {
    return voiceMessageTime(time ?? TimeInterval(0))
}

struct ComposeVoiceView: View {
    @EnvironmentObject var chatModel: ChatModel
    @Environment(\.colorScheme) var colorScheme
    var recordingFileName: String
    @Binding var recordingTime: TimeInterval?
    @Binding var recordingState: VoiceMessageRecordingState
    let cancelVoiceMessage: ((String) -> Void)
    let cancelEnabled: Bool

    @Binding var stopPlayback: Bool // value is not taken into account, only the fact it switches
    @State private var audioPlayer: AudioPlayer?
    @State private var playbackState: VoiceMessagePlaybackState = .noPlayback
    @State private var playbackTime: TimeInterval?
    @State private var startingPlayback: Bool = false

    private static let previewHeight: CGFloat = 50

    var body: some View {
        ZStack {
            if recordingState != .finished {
                recordingMode()
            } else {
                playbackMode()
            }
        }
        .padding(.vertical, 1)
        .frame(height: ComposeVoiceView.previewHeight)
        .background(colorScheme == .light ? sentColorLight : sentColorDark)
        .frame(maxWidth: .infinity)
        .padding(.top, 8)
    }

    private func recordingMode() -> some View {
        ZStack {
            HStack(alignment: .center, spacing: 8) {
                playPauseIcon("play.fill", Color(uiColor: .tertiaryLabel))
                Text(voiceMessageTime_(recordingTime))
                Spacer()
                if cancelEnabled {
                    cancelButton()
                }
            }
            .padding(.trailing, 12)

            ProgressBar(length: MAX_VOICE_MESSAGE_LENGTH, progress: $recordingTime)
        }
    }

    private func playbackMode() -> some View {
        ZStack {
            HStack(alignment: .center, spacing: 8) {
                switch playbackState {
                case .noPlayback:
                    Button {
                        startPlayback()
                    } label: {
                        playPauseIcon("play.fill")
                    }
                    Text(voiceMessageTime_(recordingTime))
                case .playing:
                    Button {
                        audioPlayer?.pause()
                        playbackState = .paused
                    } label: {
                        playPauseIcon("pause.fill")
                    }
                    Text(voiceMessageTime_(playbackTime))
                case .paused:
                    Button {
                        audioPlayer?.play()
                        playbackState = .playing
                    } label: {
                        playPauseIcon("play.fill")
                    }
                    Text(voiceMessageTime_(playbackTime))
                }
                Spacer()
                if cancelEnabled {
                    cancelButton()
                }
            }
            .padding(.trailing, 12)

            if let recordingLength = recordingTime {
                ProgressBar(length: recordingLength, progress: $playbackTime)
            }
        }
        .onChange(of: stopPlayback) { _ in
            audioPlayer?.stop()
        }
        .onDisappear {
            audioPlayer?.stop()
        }
        .onChange(of: chatModel.stopPreviousRecPlay) { _ in
            if !startingPlayback {
                audioPlayer?.stop()
                playbackState = .noPlayback
                playbackTime = TimeInterval(0)
            } else {
                startingPlayback = false
            }
        }
    }

    private func playPauseIcon(_ image: String, _ color: Color = .accentColor) -> some View {
        Image(systemName: image)
            .resizable()
            .aspectRatio(contentMode: .fit)
            .frame(width: 20, height: 20)
            .foregroundColor(color)
            .padding(.leading, 12)
    }

    private func cancelButton() -> some View {
        Button {
            audioPlayer?.stop()
            cancelVoiceMessage(recordingFileName)
        } label: {
            Image(systemName: "multiply")
        }
    }

    private struct ProgressBar: View {
        var length: TimeInterval
        @Binding var progress: TimeInterval?

        var body: some View {
            GeometryReader { geometry in
                ZStack {
                    Rectangle()
                        .fill(Color.accentColor)
                        .frame(width: min(CGFloat((progress ?? TimeInterval(0)) / length) * geometry.size.width, geometry.size.width), height: 3)
                        .animation(.linear, value: progress)
                }
                .frame(height: ComposeVoiceView.previewHeight - 1, alignment: .bottom) // minus 1 is for the bottom padding
            }
        }
    }

    private func startPlayback() {
        startingPlayback = true
        chatModel.stopPreviousRecPlay.toggle()
        audioPlayer = AudioPlayer(
            onTimer: { playbackTime = $0 },
            onFinishPlayback: {
                playbackState = .noPlayback
                playbackTime = recordingTime // animate progress bar to the end
            }
        )
        audioPlayer?.start(fileName: recordingFileName)
        playbackTime = TimeInterval(0)
        playbackState = .playing
    }
}

struct ComposeVoiceView_Previews: PreviewProvider {
    static var previews: some View {
        ComposeVoiceView(
            recordingFileName: "voice.m4a",
            recordingTime: Binding.constant(TimeInterval(20)),
            recordingState: Binding.constant(VoiceMessageRecordingState.recording),
            cancelVoiceMessage: { _ in },
            cancelEnabled: true,
            stopPlayback: Binding.constant(false)
        )
        .environmentObject(ChatModel())
    }
}
