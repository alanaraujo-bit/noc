# Noc — regras R8.
# kotlinx.serialization, Room, OkHttp, CameraX e ML Kit trazem regras próprias (consumer rules).

# Modelos serializáveis do app (nomes de campos viram chaves JSON persistidas no banco)
-keep,includedescriptorclasses class com.noc.app.chat.**$$serializer { *; }
-keepclassmembers class com.noc.app.chat.** { *** Companion; }
-keepclasseswithmembers class com.noc.app.chat.** { kotlinx.serialization.KSerializer serializer(...); }

# Parser de Markdown da JetBrains usa tipos de elemento por identidade; mantém as classes de tipos
-keep class org.intellij.markdown.** { *; }

# Destaque de sintaxe (dev.snipme.highlights) usa serialização internamente
-keep class dev.snipme.highlights.** { *; }

# Coroutines (nomes em stack traces legíveis não são necessários; só evita avisos)
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.slf4j.**
