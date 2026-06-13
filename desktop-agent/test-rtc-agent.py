#!/usr/bin/env python3
import asyncio
import importlib.util
import json
from pathlib import Path

from PIL import Image


MODULE_PATH = Path(__file__).with_name("bhzn_desktop_agent.py")
spec = importlib.util.spec_from_file_location("bhzn_desktop_agent", MODULE_PATH)
agent_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent_module)


class FakeConfig:
    device_id = "TEST-RTC"


class FakeAgent:
    def __init__(self):
        self.config = FakeConfig()
        self.frames = 0
        self.input_events = []
        self.control_enabled = True

    def capture_image_for_rtc(self, quality=None):
        self.frames += 1
        color = (self.frames % 255, 80, 120)
        return Image.new("RGB", (160, 90), color)

    def handle_input(self, payload):
        self.input_events.append(payload)


async def wait_connected(*pcs):
    deadline = asyncio.get_running_loop().time() + 10
    while asyncio.get_running_loop().time() < deadline:
        states = {pc.connectionState for pc in pcs}
        if states <= {"connected", "completed"}:
            return
        if "failed" in states or "closed" in states:
            raise AssertionError(f"peer connection failed: {states}")
        await asyncio.sleep(0.05)
    raise AssertionError(f"timed out waiting for peer connection: {[pc.connectionState for pc in pcs]}")


async def run_loopback():
    if not agent_module.AIORTC_AVAILABLE:
        raise AssertionError("aiortc is not available")

    parsed = agent_module.DesktopAgent.parse_remote_ice_candidate(
        object(),
        {
            "candidate": "candidate:1 1 UDP 2122252543 192.0.2.1 54321 typ host",
            "sdpMid": "0",
            "sdpMLineIndex": 0,
        },
    )
    assert parsed.sdpMid == "0"
    assert parsed.sdpMLineIndex == 0

    fake_agent = FakeAgent()
    controller_pc = agent_module.RTCPeerConnection()
    agent_pc = agent_module.RTCPeerConnection()
    track_future = asyncio.get_running_loop().create_future()
    message_future = asyncio.get_running_loop().create_future()
    channel_open = asyncio.Event()

    controller_pc.addTransceiver("video", direction="recvonly")
    control_channel = controller_pc.createDataChannel("control", ordered=True)

    @controller_pc.on("track")
    def on_track(track):
        if not track_future.done():
            track_future.set_result(track)

    @control_channel.on("open")
    def on_control_open():
        channel_open.set()

    @control_channel.on("message")
    def on_control_message(message):
        if not message_future.done():
            message_future.set_result(json.loads(str(message)))

    agent_pc.addTrack(agent_module.DesktopVideoTrack(fake_agent))

    @agent_pc.on("datachannel")
    def on_datachannel(channel):
        if channel.label != "control":
            return

        @channel.on("message")
        def on_message(message):
            agent_module.DesktopAgent.handle_rtc_control_message(fake_agent, "loopback-session", channel, message)

    try:
        offer = await controller_pc.createOffer()
        await controller_pc.setLocalDescription(offer)
        await agent_pc.setRemoteDescription(controller_pc.localDescription)
        answer = await agent_pc.createAnswer()
        await agent_pc.setLocalDescription(answer)
        await controller_pc.setRemoteDescription(agent_pc.localDescription)

        await wait_connected(controller_pc, agent_pc)
        remote_track = await asyncio.wait_for(track_future, timeout=10)
        frame = await asyncio.wait_for(remote_track.recv(), timeout=10)
        assert frame.width == 160
        assert frame.height == 90
        assert fake_agent.frames > 0

        await asyncio.wait_for(channel_open.wait(), timeout=10)
        control_channel.send(
            json.dumps(
                {
                    "type": "input",
                    "inputId": "loopback-input",
                    "action": "move",
                    "x": 10,
                    "y": 20,
                }
            )
        )
        result = await asyncio.wait_for(message_future, timeout=10)
        assert result["type"] == "input-result"
        assert result["ok"] is True
        assert result["inputId"] == "loopback-input"
        assert fake_agent.input_events and fake_agent.input_events[0]["sessionId"] == "loopback-session"
    finally:
        await controller_pc.close()
        await agent_pc.close()


def main():
    asyncio.run(run_loopback())
    print("rtc agent loopback ok")


if __name__ == "__main__":
    main()
