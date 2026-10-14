package zm.agent.tracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            SmsMessage[] ms = Telephony.Sms.Intents.getMessagesFromIntent(i);
            if (ms == null || ms.length == 0) return;
            StringBuilder sb = new StringBuilder();
            for (SmsMessage m : ms) sb.append(m.getMessageBody());
            Store.process(c, sb.toString(), System.currentTimeMillis());
        } catch (Exception ignored) { }
    }
}
