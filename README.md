# Synergy Radio — Application Android (Correctif Streaming & Background)

Ce dépôt contient le projet Android corrigé et stabilisé pour **Synergy Radio**.

## 🛠️ Problèmes résolus (Bug Critique)
1. **Freeze de l'interface utilisateur** : 
   - Ajout de la gestion du cycle de vie de la WebView (`onResume()`, `onPause()`) dans `MainActivity.kt`.
   - Ajout de la demande de permission runtime `POST_NOTIFICATIONS` obligatoire sous Android 13+ (API 33/34).
   - Remplacement des appels bloquants dans la WebView par un `WebChromeClient` sécurisé.
2. **Streaming audio ininterrompu en arrière-plan** :
   - Intégration native de **Google ExoPlayer (AndroidX Media3)** dans `RadioService.kt`.
   - L'audio n'est plus tributaire de la WebView : il continue de jouer même écran verrouillé ou application minimisée.
   - Ajout d'un `WakeLock` audio pour éviter que le système Android ne coupe le CPU en mode veille profonde (*Doze Mode*).
   - Reconnexion automatique au flux Icecast en cas de micro-coupure réseau.
3. **Contrôles Play / Pause actifs sur la notification & écran de verrouillage** :
   - Implémentation complète de `MediaSessionCompat` et `NotificationCompat.MediaStyle`.
   - Les boutons Play / Pause du panneau de notification contrôlent directement ExoPlayer sans passer par la WebView.

---

## 🚀 Comment envoyer ce projet sur votre GitHub

Ouvrez votre terminal dans le dossier du projet décompressé, puis lancez :

```bash
# 1. Initialiser git si ce n'est pas déjà fait
git init

# 2. Vérifier les fichiers modifiés
git status

# 3. Ajouter tous les fichiers corrigés
git add .

# 4. Créer le commit
git commit -m "Fix: Streaming Icecast arrière-plan avec ExoPlayer Media3 et correction du freeze UI"

# 5. Définir la branche principale
git branch -M main

# 6. Lier à votre dépôt GitHub distant (si nouveau projet)
# git remote add origin https://github.com/VOTRE-NOM-UTILISATEUR/SynergyRadioAndroid.git

# 7. Envoyer sur GitHub
git push -u origin main --force
```

---

## 📱 Compilation dans Android Studio
1. Ouvrez **Android Studio** -> **Open** -> Sélectionnez le dossier `SynergyRadioAndroid-main`.
2. Laissez Gradle synchroniser les dépendances (notamment `androidx.media3:media3-exoplayer:1.2.1`).
3. Connectez votre téléphone Android (avec le mode débogage USB activé) ou lancez un émulateur.
4. Cliquez sur le bouton vert **Run 'app'** ou générez l'APK via **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
