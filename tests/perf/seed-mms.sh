#!/bin/bash
# seed-mms.sh [count]: adds picture messages (MMS) to the (rooted) emulator, on top of seed.sh's SMS.
#   Each MMS: one 1280x960 JPEG (a camera-sized photo, ~150 KB) and a text part, received in one of the first
#   conversations seed.sh made (threads 1000-1019), dated newer than every SMS there, so those 20 conversations
#   have a picture as their newest message (the case the MMS switch changes most).
#   Written straight into mmssms.db (pdu / part / addr) and the provider's app_parts folder, like seed.sh.
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
N=${1:-60}
adb root >/dev/null; adb wait-for-device
TP=/data/user_de/0/com.android.providers.telephony
DB=$TP/databases/mmssms.db
PARTS=$TP/app_parts
adb shell "ls $DB" >/dev/null || { echo "no mmssms.db"; exit 1; }
W=$(mktemp -d)
python3 - "$W" "$N" <<'EOF'
import os, random, sys
from PIL import Image, ImageDraw
w, n = sys.argv[1], int(sys.argv[2])
random.seed(5)
os.makedirs(os.path.join(w, "parts"), exist_ok=True)
now = 1793000000  # seconds; seed.sh's newest SMS is 1793000000000 ms
sql = ["BEGIN;"]
for i in range(n):
    thread = 1000 + i % 20
    date = now + 3600 * (n - i)  # newer than any seeded SMS
    img = Image.new("RGB", (1280, 960), (random.randrange(256), random.randrange(256), random.randrange(256)))
    d = ImageDraw.Draw(img)
    for _ in range(300):  # some detail so the JPEG is photo-sized
        x, y = random.randrange(1280), random.randrange(960)
        d.ellipse((x, y, x + random.randrange(20, 120), y + random.randrange(20, 120)),
                  fill=(random.randrange(256), random.randrange(256), random.randrange(256)))
    name = "PART_seed_%03d.jpg" % i
    img.save(os.path.join(w, "parts", name), "JPEG", quality=85)
    mid = 50000 + i
    sql.append("INSERT INTO pdu (_id, thread_id, date, date_sent, msg_box, read, seen, m_type, v, ct_t, sub, sub_id, text_only) "
               "VALUES (%d, %d, %d, %d, 1, 1, 1, 132, 18, 'application/vnd.wap.multipart.related', NULL, -1, 0);"
               % (mid, thread, date, date))
    sql.append("INSERT INTO part (mid, seq, ct, name, cl, _data) VALUES (%d, 0, 'image/jpeg', '%s', '%s', '/data/user_de/0/com.android.providers.telephony/app_parts/%s');"
               % (mid, name, name, name))
    sql.append("INSERT INTO part (mid, seq, ct, cl, chset, text) VALUES (%d, 1, 'text/plain', 'text_0.txt', 106, 'Photo %d');" % (mid, i))
    sql.append("INSERT INTO addr (msg_id, address, type, charset) VALUES (%d, '+1555%07d', 137, 106);" % (mid, thread - 1000 + 1))
    sql.append("INSERT INTO addr (msg_id, address, type, charset) VALUES (%d, 'insert-address-token', 151, 106);" % mid)
sql.append("COMMIT;")
open(os.path.join(w, "mms.sql"), "w").write("\n".join(sql) + "\n")
EOF
adb shell "mkdir -p $PARTS && chown radio:radio $PARTS && chmod 771 $PARTS"
adb push "$W/parts/." $PARTS/ >/dev/null && echo "pushed $(ls $W/parts | wc -l) pictures ($(du -sh $W/parts | cut -f1))"
adb shell "chown radio:radio $PARTS/PART_seed_* && chmod 660 $PARTS/PART_seed_* && chcon \$(ls -Zd $DB | cut -d' ' -f1) $PARTS/PART_seed_* $PARTS"
adb push "$W/mms.sql" /data/local/tmp/kdeconnect-mms.sql >/dev/null
adb shell "sqlite3 $DB < /data/local/tmp/kdeconnect-mms.sql" && echo "mms seeded: $(adb shell "sqlite3 $DB 'select count(*) from pdu'" | tr -d '\r') pdu, $(adb shell "sqlite3 $DB 'select count(*) from part'" | tr -d '\r') parts"
rm -rf "$W"
