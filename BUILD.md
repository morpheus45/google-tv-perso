# Google TV Perso — Guide de compilation et installation

## Prérequis
- Android Studio **Koala** (2024.1.1) ou supérieur — [télécharger](https://developer.android.com/studio)
- OU JDK 17 + Android SDK (ligne de commande)
- Une TV Android / Google TV avec le **débogage ADB activé**

---

## 1. Compiler l'APK

### Via Android Studio (recommandé)
1. Ouvrir Android Studio → **File → Open** → sélectionner le dossier `google-tv-perso/`
2. Laisser Gradle synchroniser
3. **Build → Build Bundle(s)/APK(s) → Build APK(s)**
4. L'APK se trouve dans `app/build/outputs/apk/debug/app-debug.apk`

### Via ligne de commande (Windows)
```cmd
cd "C:\Users\cedri\OneDrive\Desktop\vod claude\google-tv-perso"
gradlew.bat assembleRelease
```

---

## 2. Installer sur la TV via ADB

### Activer le débogage sur la TV
1. **Paramètres → À propos → Infos** → appuyer 7× sur **Numéro de build**
2. **Options développeur → Débogage ADB** → Activer
3. Paramètres → Réseau → noter l'adresse IP de la TV

### Installer l'APK
```cmd
adb connect 192.168.X.X:5555
adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

---

## 3. Définir comme launcher par défaut (remplacer Google TV)

### Méthode 1 — Interface TV (la plus simple)
1. Après installation, appuyer sur la touche **HOME** de la télécommande
2. Android proposera de choisir le launcher → sélectionner **Google TV Perso**
3. Cocher **"Toujours"** pour en faire le launcher permanent

### Méthode 2 — ADB (si la méthode 1 ne fonctionne pas)
```cmd
adb shell cmd package set-home-activity com.googletvperso.app/.HomeActivity
```

### Méthode 3 — Sur certaines TV avec root ou ADB Shell
```cmd
adb shell settings put secure default_launcher com.googletvperso.app/.HomeActivity
```

---

## 4. Démarrage automatique

Le `BootReceiver` lance l'app dès le démarrage de la TV.
Si l'app est définie comme launcher HOME, elle s'ouvrira automatiquement au démarrage.

---

## 5. Revenir à Google TV (si besoin)

```cmd
adb shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```
Ou dans Paramètres TV → Applications → Launcher par défaut.

---

## Structure du projet

```
google-tv-perso/
├── app/src/main/
│   ├── AndroidManifest.xml        ← launcher HOME + LEANBACK_LAUNCHER
│   ├── assets/
│   │   └── index.html             ← Interface Google TV (HTML/CSS/JS)
│   └── java/com/googletvperso/app/
│       ├── HomeActivity.java      ← WebView + relais D-pad
│       ├── VlcPlayerActivity.java ← Lecteur VLC embarqué
│       └── BootReceiver.java      ← Démarrage automatique
```

## Gradlew (si non présent)
Copier `gradlew`, `gradlew.bat` et `gradle/` depuis pipsiflix-android/ :
```cmd
xcopy /E /Y "..\pipsiflix-android\gradle" "gradle\"
copy "..\pipsiflix-android\gradlew" .
copy "..\pipsiflix-android\gradlew.bat" .
```
