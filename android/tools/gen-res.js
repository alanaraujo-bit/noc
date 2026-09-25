// Gera os recursos estáticos do app (ícone, temas, xml). Rodar: node tools/gen-res.js
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..', 'app', 'src', 'main');
const w = (rel, content) => {
  const f = path.join(root, rel);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  fs.writeFileSync(f, content.trim() + '\n');
};

w('res/values/strings.xml', `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Noc</string>
</resources>`);

w('res/values/colors.xml', `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="noc_bg">#F5F3EF</color>
    <color name="noc_icon_bg">#141311</color>
    <color name="noc_ember">#F08A4B</color>
</resources>`);

w('res/values-night/colors.xml', `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="noc_bg">#111110</color>
</resources>`);

w('res/values/themes.xml', `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Noc" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/noc_bg</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
    <style name="Theme.Noc.Splash" parent="Theme.SplashScreen">
        <item name="windowSplashScreenBackground">@color/noc_bg</item>
        <item name="windowSplashScreenAnimatedIcon">@drawable/ic_splash</item>
        <item name="postSplashScreenTheme">@style/Theme.Noc</item>
    </style>
</resources>`);

w('res/values-night/themes.xml', `<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Noc" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/noc_bg</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>`);

// Marca: um ponto de brasa (a sua máquina) envolto por um anel aberto (a conexão).
const dot = 'M54,54m-9,0a9,9 0,1 1,18 0a9,9 0,1 1,-18 0';
const ring = 'M72.4,41.2 A22,22 0 1,1 64,34.4';
w('res/drawable/ic_launcher_foreground.xml', `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="@color/noc_ember" android:pathData="${dot}"/>
    <path android:strokeColor="#EEECE8" android:strokeWidth="5" android:strokeLineCap="round" android:pathData="${ring}"/>
</vector>`);
w('res/drawable/ic_launcher_monochrome.xml', `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FFFFFF" android:pathData="${dot}"/>
    <path android:strokeColor="#FFFFFF" android:strokeWidth="5" android:strokeLineCap="round" android:pathData="${ring}"/>
</vector>`);
w('res/drawable/ic_splash.xml', `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="@color/noc_ember" android:pathData="${dot}"/>
    <path android:strokeColor="@color/noc_ember" android:strokeAlpha="0.5" android:strokeWidth="5" android:strokeLineCap="round" android:pathData="${ring}"/>
</vector>`);
w('res/drawable/ic_stat_noc.xml', `<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFF" android:pathData="M12,12m-3,0a3,3 0,1 1,6 0a3,3 0,1 1,-6 0"/>
    <path android:strokeColor="#FFFFFF" android:strokeWidth="2" android:strokeLineCap="round" android:pathData="M18.2,7.6 A7.5,7.5 0 1,1 15.3,5.3"/>
</vector>`);
w('res/drawable/ic_launcher_background.xml', `<shape xmlns:android="http://schemas.android.com/apk/res/android"><solid android:color="@color/noc_icon_bg"/></shape>`);
const adaptive = `<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>`;
w('res/mipmap-anydpi-v26/ic_launcher.xml', adaptive);
w('res/mipmap-anydpi-v26/ic_launcher_round.xml', adaptive);

w('res/xml/network_security_config.xml', `<?xml version="1.0" encoding="utf-8"?>
<!--
  ws:// é permitido só porque a conexão direta na rede local não tem TLS. Isso é seguro aqui:
  todo o conteúdo já trafega cifrado de ponta a ponta pelo protocolo Noc (ECDH P-256 + AES-256-GCM,
  com a chave do PC fixada no pareamento). O relay na internet usa wss://.
-->
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors><certificates src="system"/></trust-anchors>
    </base-config>
</network-security-config>`);

w('res/xml/data_extraction_rules.xml', `<?xml version="1.0" encoding="utf-8"?>
<!-- Privacidade: conversas nunca vão para backup em nuvem nem são copiadas para outro aparelho. -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root"/><exclude domain="database"/><exclude domain="sharedpref"/><exclude domain="file"/>
    </cloud-backup>
    <device-transfer>
        <exclude domain="root"/><exclude domain="database"/><exclude domain="sharedpref"/><exclude domain="file"/>
    </device-transfer>
</data-extraction-rules>`);

w('res/xml/file_paths.xml', `<?xml version="1.0" encoding="utf-8"?>
<paths><cache-path name="exports" path="exports/"/></paths>`);

w('AndroidManifest.xml', `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".NocApp"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="false"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:networkSecurityConfig="@xml/network_security_config"
        android:supportsRtl="true"
        android:theme="@style/Theme.Noc">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:theme="@style/Theme.Noc.Splash"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="noc" android:host="pair" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.GenerationService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="\${applicationId}.files"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/file_paths" />
        </provider>
    </application>
</manifest>`);

console.log('recursos gerados');
