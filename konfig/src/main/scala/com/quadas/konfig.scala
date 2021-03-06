package com.quadas

import com.typesafe.config.{ Config, ConfigMemorySize }
import shapeless._
import shapeless.labelled.{ FieldType, field }

import scala.collection.JavaConverters._
import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.Try

package konfig {

  trait ConfigReader[T] {
    def read(c: Config, path: String): T
  }

  object ConfigReader {
    def of[T](f: (Config, String) => T) = new ConfigReader[T] {
      def read(c: Config, path: String) = f(c, path)
    }

    def fromString[T](f: String => T) = new ConfigReader[T] {
      def read(c: Config, path: String) = f(c.getString(path))
    }
  }

  trait SubtypeHint {
    def fieldName(): String

    def matchType(fieldValue: String, typeName: String): Boolean
  }

  trait KeyStyle {
    def style(key: String): String
  }

  object KeyStyle {
    object Same extends KeyStyle {
      override def style(key: String): String = key
    }

    // someVeryLongName -> some-very-long-name, ASCII SUPPORT ONLY
    object Hyphen extends KeyStyle {
      override def style(key: String): String = {
        "[A-Z]".r.replaceAllIn(key, "-" + _.matched.toLowerCase).stripPrefix("-")
      }
    }
  }

  trait ProductReaders {
    implicit val hNilReader = new ConfigReader[HNil] {
      override def read(c: Config, path: String) = HNil
    }

    implicit def hListReader[Key <: Symbol, Head, Tail <: HList](
      implicit
      key: Witness.Aux[Key],
      keyStyle: KeyStyle,
      cr: Lazy[ConfigReader[Head]],
      tail: Lazy[ConfigReader[Tail]]
    ): ConfigReader[FieldType[Key, Head] :: Tail] = new ConfigReader[FieldType[Key, Head] :: Tail] {
      override def read(c: Config, path: String) = {
        val v = cr.value.read(c.getConfig(path), keyStyle.style(key.value.name))
        field[Key](v) :: tail.value.read(c, path)
      }
    }

    implicit val cNilReader = new ConfigReader[CNil] {
      import com.typesafe.config.ConfigException
      override def read(c: Config, path: String): CNil = throw new ConfigException.Generic("no matching subtype, please specify one")
    }

    implicit def coproductReader[Key <: Symbol, Head, Tail <: Coproduct](
      implicit
      key: Witness.Aux[Key],
      subtypeHint: SubtypeHint,
      cr: Lazy[ConfigReader[Head]],
      tail: Lazy[ConfigReader[Tail]]
    ): ConfigReader[FieldType[Key, Head] :+: Tail] = new ConfigReader[FieldType[Key, Head] :+: Tail] {
      override def read(c: Config, path: String) = {
        val subTypeValue = c.getConfig(path).getString(subtypeHint.fieldName())
        if (subtypeHint.matchType(subTypeValue, key.value.name)) {
          Inl(field[Key](cr.value.read(c, path)))
        } else {
          Inr(tail.value.read(c, path))
        }
      }
    }

    implicit def productReader[T, Repr](
      implicit
      gen: LabelledGeneric.Aux[T, Repr],
      cr: Lazy[ConfigReader[Repr]]
    ): ConfigReader[T] = new ConfigReader[T] {
      override def read(c: Config, path: String) = {
        gen.from(cr.value.read(c, path))
      }
    }
  }

  trait StandardReaders {
    implicit val stringReader: ConfigReader[String] = ConfigReader.fromString(identity)

    implicit val intReader: ConfigReader[Int] = ConfigReader.of(_.getInt(_))

    implicit val longReader: ConfigReader[Long] = ConfigReader.of(_.getLong(_))

    implicit val booleanReader: ConfigReader[Boolean] = ConfigReader.of(_.getBoolean(_))

    implicit val floatReader: ConfigReader[Float] = ConfigReader.of(_.getDouble(_).toFloat)

    implicit val doubleReader: ConfigReader[Double] = ConfigReader.of(_.getDouble(_))

    implicit val bigDecimalReader: ConfigReader[BigDecimal] = ConfigReader.fromString(BigDecimal.apply)

    implicit val finiteDurationReader: ConfigReader[FiniteDuration] = ConfigReader.of(_.getDuration(_).toNanos.nanos)

    implicit val memorySizeReader: ConfigReader[ConfigMemorySize] = ConfigReader.of(_.getMemorySize(_))

    implicit val configReader: ConfigReader[Config] = ConfigReader.of(_.getConfig(_))

    implicit def strMapReader[T](implicit cr: ConfigReader[T]): ConfigReader[Map[String, T]] = {
      val _PATH = "_"
      new ConfigReader[Map[String, T]] {
        override def read(c: Config, path: String) = {
          val co = c.getConfig(path)
          co.entrySet()
            .asScala
            .map {
              ent =>
                ent.getKey -> cr.read(ent.getValue.atKey(_PATH), _PATH)
            }
            .toMap
        }
      }
    }

    implicit def listReader[C[_], T](implicit cr: ConfigReader[T], cbf: CanBuildFrom[C[T], T, C[T]]): ConfigReader[C[T]] = {
      val _PATH = "_"
      new ConfigReader[C[T]] {
        override def read(c: Config, path: String): C[T] = {
          val coll = cbf()
          c.getList(path)
            .asScala
            .foreach(v => coll += cr.read(v.atKey(_PATH), _PATH))

          coll.result()
        }
      }
    }

    implicit def optionReader[T](implicit cr: ConfigReader[T]): ConfigReader[Option[T]] = {
      new ConfigReader[Option[T]] {
        override def read(c: Config, path: String): Option[T] = {
          if (c.hasPath(path)) {
            Some(cr.read(c, path))
          } else None
        }
      }
    }

    implicit def tryReader[T](implicit cr: ConfigReader[T]): ConfigReader[Try[T]] = {
      new ConfigReader[Try[T]] {
        override def read(c: Config, path: String): Try[T] = Try(cr.read(c, path))
      }
    }
  }

}

package object konfig extends ProductReaders with StandardReaders {
  implicit val keyStyle = KeyStyle.Hyphen

  implicit object subtypeHint extends SubtypeHint {
    val LOCALE = java.util.Locale.US
    override def fieldName(): String = "type"
    override def matchType(fieldValue: String, typeName: String): Boolean = typeName.toLowerCase(LOCALE).startsWith(fieldValue.toLowerCase(LOCALE))
  }

  implicit class EnrichedConfig(val c: Config) extends AnyVal {
    def read[T](path: String)(implicit cr: ConfigReader[T]): T = {
      cr.read(c, path)
    }

    def read[T]()(implicit cr: ConfigReader[T]): T = {
      val _KEY = "_"
      c.atKey(_KEY).read[T](_KEY)
    }
  }
}
