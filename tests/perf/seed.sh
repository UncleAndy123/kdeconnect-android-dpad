#!/bin/bash
# Seeds the (rooted) emulator with a heavy phone's worth of data:
#   SMS: 80 conversations, 40 messages each + one 3000-message conversation (written straight into mmssms.db)
#   contacts: 200 contacts with a name and a number (through the contacts provider, so lookup keys are real)
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
adb root >/dev/null; adb wait-for-device
DB=/data/user_de/0/com.android.providers.telephony/databases/mmssms.db
adb shell "ls $DB" || { echo "no mmssms.db"; exit 1; }
python3 - > /tmp/kdeconnect-seed.sql <<'EOF'
import random
random.seed(1)
now = 1793000000000
print("BEGIN;")
tid = 1000
for t in range(81):
    addr = "+1555%07d" % (t + 1)
    n = 3000 if t == 0 else 40
    print(f"INSERT INTO canonical_addresses(_id,address) VALUES({tid},'{addr}');")
    print(f"INSERT INTO threads(_id,date,message_count,recipient_ids,snippet,snippet_cs,read,type,error,has_attachment) VALUES({tid},{now},0,'{tid}','',0,1,0,0,0);")
    for i in range(n):
        d = now - (t * 3600000) - i * 60000
        typ = 1 if i % 2 else 2
        body = f"Message {i} in conversation {t}: " + "lorem ipsum dolor sit amet " * random.randint(1, 4)
        print(f"INSERT INTO sms(thread_id,address,date,date_sent,read,status,type,body,seen) VALUES({tid},'{addr}',{d},{d},1,-1,{typ},'{body}',1);")
    tid += 1
print("COMMIT;")
EOF
adb push /tmp/kdeconnect-seed.sql /data/local/tmp/seed.sql >/dev/null
adb shell "sqlite3 $DB < /data/local/tmp/seed.sql" && echo "sms seeded: $(adb shell "sqlite3 $DB 'select count(*) from sms'")"
adb shell "pkill -f com.android.phone; true"
# contacts
cat > /tmp/kdeconnect-seedc.sh <<'EOF'
i=1
while [ $i -le 200 ]; do
  content insert --uri content://com.android.contacts/raw_contacts --bind starred:i:0
  content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$i --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:"Contact Number$i"
  content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:$i --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind data1:s:+1555$(printf %07d $i) --bind data2:i:2
  i=$((i+1))
done
EOF
adb push /tmp/kdeconnect-seedc.sh /data/local/tmp/seedc.sh >/dev/null
adb shell sh /data/local/tmp/seedc.sh >/dev/null 2>&1
echo "contacts seeded: $(adb shell content query --uri content://com.android.contacts/contacts --projection _id | grep -c Row)"
