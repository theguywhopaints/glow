import os, asyncio, time
from kasa import Discover, Module
async def main():
    u, p = [l.strip() for l in open(".tapo").read().splitlines()[:2]]
    dev = await Discover.discover_single(os.environ["BULB_IP"], username=u, password=p)
    for _ in range(8):
        await dev.update(); l = dev.modules[Module.Light]
        print(time.strftime("%H:%M:%S"), "on" if dev.is_on else "off", "hue/sat/bri:", tuple(l.hsv)); await asyncio.sleep(1.2)
    await dev.disconnect()
asyncio.run(main())
