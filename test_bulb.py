import os, asyncio, sys, time
from kasa import Discover, Module

async def main():
    user, pw = [l.strip() for l in open(".tapo").read().splitlines()[:2]]
    dev = await Discover.discover_single(os.environ["BULB_IP"], username=user, password=pw)
    await dev.update()
    light = dev.modules[Module.Light]
    print("model:", dev.model, "| on:", dev.is_on, "| hsv:", light.hsv, "| brightness:", light.brightness)
    orig_on, orig_hsv = dev.is_on, light.hsv
    await dev.turn_on()
    # Speed test: cycle through colours as fast as the bulb accepts.
    t = time.time(); n = 0
    for hue in range(0, 360, 30):
        await light.set_hsv(hue, 100, 80); n += 1
    dt = time.time() - t
    print(f"{n} colour changes in {dt:.2f}s -> {n/dt:.1f} per second")
    await light.set_hsv(*orig_hsv)
    if not orig_on: await dev.turn_off()
    await dev.disconnect()
asyncio.run(main())
