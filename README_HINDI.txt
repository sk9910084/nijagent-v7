NijAgent v7: APK kaise banayein (GitHub se, free)
=================================================

PEHLE: purana NijAgent v6 uninstall karo (uski signing key alag thi, isliye naya uske upar install nahi hoga).
Is v7 se aage har naya APK v7 ke upar install hoga aur skills/history bachi rahengi.

STEP 1  github.com par free account banao.
STEP 2  New repository banao. Naam: nijagent. Isko PRIVATE rakho (isme signing key hai).
STEP 3  Is zip ko extract karo. Andar ke SAARE files/folders repository me upload karo
        (Add file > Upload files). PC ke browser me folders drag-drop ho jaate hain.
        Dhyaan: ".github" folder (dot se shuru) bhi jaana chahiye. Agar nahi gaya to:
        Add file > Create new file > naam me ".github/workflows/build.yml" likho
        aur build.yml ka content paste karo.
        "nijagent.keystore" file bhi upload honi chahiye.
STEP 4  Repository me "Actions" tab kholo. "Build NijAgent APK" chalta dikhega
        (na dikhe to us workflow par "Run workflow" dabao). 4-6 minute lagte hain.
STEP 5  Green tick aane par us run ko kholo. Neeche "Artifacts" me "NijAgent-v7-apk" download karo
        (zip hai, andar app-release.apk hai). Phone me install karo.
        Red cross aaye to error ka screenshot mujhe bhejo.

PHONE ME PEHLI BAAR:
1. App kholo. Setup me "Accessibility Settings kholo" dabao, NijAgent ON karo.
   Android 13+ par NijAgent grey ho to: App info > upar teen dots > "Allow restricted settings",
   phir dobara Accessibility me jao.
2. aistudio.google.com/apikey se free Gemini key banao aur app me dalo (Settings > AI & Models).
3. Home par likho: "YouTube pe comedy video chalao".

ZARURI BAATEN:
- Paise/payment wale kaam se pehle agent aapse haan/na poochta hai (ye band nahi hota).
- Password, PIN, OTP agent kabhi khud nahi bharta; aapko khud bharna padta hai.
- Bank apps aksar screen control block karti hain. Wahan agent kaam na kare to normal hai.
- Skill pehli baar Gemini se banta hai, uske baad bina Gemini ke chalta hai. Galat ho to 👎 dabao.
- Gemini API key code me nahi hai. Wo sirf aapke phone me rehti hai.

Naya version: files badlo, GitHub me upload karo, Actions naya APK bana dega.
