package com.zbrown.droidsteal.helper;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.util.HashMap;

public class DBHelper {

    private static SQLiteDatabase droidstealDB = null;
    public static final String DROIDSTEAL_DBNAME = "droidsteal";

    public static final String CREATE_PREFERENCES = "CREATE TABLE IF NOT EXISTS DROIDSTEAL_PREFERENCES "
            + "(id integer primary key autoincrement, " + "name  varchar(100)," + "value varchar(100));";

    public static final String CREATE_BLACKLIST = "CREATE TABLE IF NOT EXISTS DROIDSTEAL_BLACKLIST "
            + "(id integer primary key autoincrement, " + "domain varchar(100));";

    public static void initDB(Context c) {
        DBHelper.droidstealDB = c.openOrCreateDatabase(DROIDSTEAL_DBNAME, Context.MODE_PRIVATE, null);
        droidstealDB.execSQL(CREATE_PREFERENCES);
        droidstealDB.execSQL(CREATE_BLACKLIST);
    }

    public static boolean getGeneric(Context c) {
        initDB(c);
        Cursor cur = droidstealDB.rawQuery("SELECT * FROM DROIDSTEAL_PREFERENCES WHERE name = 'generic';", new String[]{});
        if (cur.moveToNext()) {
            String s = cur.getString(cur.getColumnIndex("value"));
            cur.close();
            droidstealDB.close();
            return Boolean.parseBoolean(s);
        } else {
            cur.close();
            droidstealDB.close();
            return false;
        }
    }

    public static HashMap<String, Object> getBlacklist(Context c) {
        HashMap<String, Object> map = new HashMap<String, Object>();
        initDB(c);
        Cursor cur = droidstealDB.rawQuery("SELECT domain FROM DROIDSTEAL_BLACKLIST;", new String[]{});

        while (cur.moveToNext()) {
            String s = cur.getString(cur.getColumnIndex("domain"));
            map.put(s, null);
        }

        cur.close();
        droidstealDB.close();
        return map;
    }

    public static void addBlacklistEntry(Context c, String name) {
        initDB(c);
        droidstealDB.execSQL("INSERT INTO DROIDSTEAL_BLACKLIST (domain) VALUES (?);", new Object[]{name});
        droidstealDB.close();
    }

    public static void setGeneric(Context c, boolean b) {
        initDB(c);
        Cursor cur = droidstealDB.rawQuery("SELECT count(id) as count FROM DROIDSTEAL_PREFERENCES where name = 'generic';",
                new String[]{});
        cur.moveToFirst();
        int count = (int) cur.getLong(cur.getColumnIndex("count"));
        if (count == 0) {
            droidstealDB.execSQL("INSERT INTO DROIDSTEAL_PREFERENCES (name, value) values ('generic', ?);",
                    new String[]{Boolean.toString(b)});
        } else {
            droidstealDB.execSQL("UPDATE DROIDSTEAL_PREFERENCES SET value=? WHERE name='generic';",
                    new String[]{Boolean.toString(b)});
        }
        droidstealDB.close();
    }

    public static void clearBlacklist(Context c) {
        initDB(c);
        droidstealDB.execSQL("DELETE FROM DROIDSTEAL_BLACKLIST;", new Object[]{});
        droidstealDB.close();
    }

    public static void setLastUpdateCheck(Context c, long date) {
        initDB(c);
        Cursor cur = droidstealDB.rawQuery("SELECT count(id) as count FROM DROIDSTEAL_PREFERENCES where name = 'update';", new String[]{});
        cur.moveToFirst();
        int count = (int) cur.getLong(cur.getColumnIndex("count"));
        if (count == 0) {
            droidstealDB.execSQL("INSERT INTO DROIDSTEAL_PREFERENCES (name, value) values ('update', ?);",
                    new String[]{Long.toString(date)});
        } else {
            droidstealDB.execSQL("UPDATE DROIDSTEAL_PREFERENCES SET value=? WHERE name='update';",
                    new String[]{Long.toString(date)});
        }
        droidstealDB.close();
    }

    public static long getLastUpdateMessage(Context c) {
        try {
            initDB(c);
            Cursor cur = droidstealDB.rawQuery("SELECT value FROM DROIDSTEAL_PREFERENCES where name = 'update';",
                    new String[]{});
            cur.moveToFirst();
            long datetime = cur.getLong(cur.getColumnIndex("value"));
            return datetime;
        } catch (Exception e) {
            Log.d(Constants.APPLICATION_TAG, "Could not load last update datetime: " + e.getLocalizedMessage());
        } finally {
            droidstealDB.close();
        }
        return 0L;
    }
}
