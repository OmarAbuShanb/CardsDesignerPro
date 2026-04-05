package dev.anonymous.cardsdesignerpro.data.serializer

import dev.anonymous.cardsdesignerpro.data.model.Template
import dev.anonymous.cardsdesignerpro.data.model.TemplateElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Central JSON configuration for the app.
 * Sealed-class polymorphism uses the `type` SerialName discriminator.
 */
object AppJson {
    val instance: Json = Json {
        serializersModule = SerializersModule {
            polymorphic(TemplateElement::class) {
                subclass(TemplateElement.CardBackground::class)
                subclass(TemplateElement.TextElement::class)
                subclass(TemplateElement.UsernameElement::class)
                subclass(TemplateElement.PasswordElement::class)
                subclass(TemplateElement.ImageElement::class)
                subclass(TemplateElement.QrElement::class)
                subclass(TemplateElement.DateElement::class)
                subclass(TemplateElement.FrameElement::class)
                subclass(TemplateElement.BackgroundDecorationElement::class)
            }
        }
        prettyPrint = false
        ignoreUnknownKeys = true   // forward-compatibility
        encodeDefaults = true
        classDiscriminator = "type"
    }

    fun encode(template: Template): String = instance.encodeToString(Template.serializer(), template)

    fun decode(json: String): Template = instance.decodeFromString(Template.serializer(), json)
}
