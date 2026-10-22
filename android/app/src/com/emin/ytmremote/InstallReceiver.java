package com.emin.ytmremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** PackageInstaller sonucunu karsilar: gerekirse sistemin onay ekranini acar. */
public class InstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
        String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    Diag.log(context, "UPD-ONAY");
                } catch (Throwable t) {
                    Diag.log(context, "UPD-ONAY-HATA " + t);
                }
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Diag.log(context, "UPD-KURULDU");
            RemoteService.lastError = "";
        } else {
            Diag.log(context, "UPD-HATA " + status + " " + msg);
        }
    }
}
