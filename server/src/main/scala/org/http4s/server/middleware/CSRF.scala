package org.http4s.server.middleware

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.Clock
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import javax.crypto.{KeyGenerator, Mac, SecretKey}

import org.http4s.headers.{Cookie => HCookie}
import org.http4s.server.Middleware
import org.http4s.util.{CaseInsensitiveString, encodeHex}
import org.http4s._

import scalaz.concurrent.Task
import scalaz.syntax.std.boolean._
import scalaz.{Kleisli, OptionT}

/** Middleware to avoid Cross-site request forgery attacks.
  * More info on CSRF at: https://www.owasp.org/index.php/Cross-Site_Request_Forgery_(CSRF)
  *
  * This middleware is modeled after the double submit cookie pattern:
  * https://www.owasp.org/index.php/Cross-Site_Request_Forgery_(CSRF)_Prevention_Cheat_Sheet#DoubleSubmit_Cookie
  *
  * When a user authenticates, `embedNew` is used to send a random CSRF value as a cookie.  (Alterntively,
  * an authenticating service can be wrapped in `withNewToken`).  Services protected by the `validaed`
  * middleware then check that the value is prsent in both the header `headerName` and the cookie `cookieName`.
  * Due to the Same-Origin policy, an attacker will be unable to reproduce this value in a
  * custom header, resulting in a `403 Forbidden` response.
  *
  * @param headerName your CSRF header name
  * @param cookieName the CSRF cookie name
  * @param key the CSRF signing key
  * @param clock clock used as a nonce
  */
final class CSRF private[middleware] (val headerName: String = "X-Csrf-Token",
                                      val cookieName: String = "csrf-token",
                                      key: SecretKey,
                                      clock: Clock = Clock.systemUTC()) {

  /** Sign our token using the current time in milliseconds as a nonce
    * Signing and generating a token is potentially a unsafe operation
    * if constructed with a bad key.
    */
  private[middleware] def signToken(token: String): Task[String] = {
    val joined = token + "-" + clock.millis()
    Task.delay {
      val mac = Mac.getInstance(CSRF.SigningAlgo)
      mac.init(key)
      val out = mac.doFinal(joined.getBytes(StandardCharsets.UTF_8))
      joined + "-" + Base64.getEncoder.encodeToString(out)
    }
  }

  /** Generate a new token **/
  private[middleware] def generateToken: Task[String] =
    signToken(CSRF.genTokenString)

  /** Decode our CSRF token and extract the original token string to sign
    */
  private[middleware] def extractRaw(token: String): OptionT[Task, String] =
    token.split("-") match {
      case Array(raw, nonce, signed) =>
        OptionT[Task, String](Task.delay {
          val mac = Mac.getInstance(CSRF.SigningAlgo)
          mac.init(key)
          val out =
            mac.doFinal((raw + "-" + nonce).getBytes(StandardCharsets.UTF_8))
          MessageDigest
            .isEqual(out, Base64.getDecoder.decode(signed))
            .option(raw)
        })
      case _ =>
        OptionT.none
    }

  /** Handle safe methods **/
  private[middleware] def validateOrEmbed(
      r: Request,
      service: HttpService): Task[MaybeResponse] =
    CSRF.cookieFromHeader(r, cookieName) match {
      case Some(c) =>
        (for {
          raw <- extractRaw(c.content)
          response <- OptionT[Task, MaybeResponse](
            service.run(r).map(Some.apply))
          newToken <- OptionT[Task, String](signToken(raw).map(Some.apply))
        } yield
          response.cata(_.addCookie(Cookie(cookieName, newToken)),
                        Response(Status.NotFound)))
          .getOrElse(Response(Status.Unauthorized))
      case None =>
        service.run(r).flatMap(embedNew)
    }

  private[middleware] def checkCSRF(r: Request,
                                    service: HttpService): Task[MaybeResponse] =
    (for {
      c1 <- CSRF.liftedCookieFromHeader(r, cookieName)
      c2 <- OptionT(Task.now(r.headers.get(CaseInsensitiveString(headerName))))
      raw1 <- extractRaw(c1.content)
      raw2 <- extractRaw(c2.value)
      response <- if (CSRF.isEqual(raw1, raw2)) {
        OptionT[Task, MaybeResponse](service(r).map(Some(_)))
      } else {
        OptionT.none[Task, MaybeResponse]
      }
      newToken <- OptionT[Task, String](signToken(raw1).map(Some(_)))
    } yield
      response.cata(_.addCookie(Cookie(cookieName, newToken)),
                    Response(Status.NotFound)))
      .getOrElse(Response(Status.Unauthorized))

  private[middleware] def filter(predicate: Request => Boolean,
                                 r: Request,
                                 service: HttpService) = {
    if (predicate(r)) {
      validateOrEmbed(r, service)
    } else {
      checkCSRF(r, service)
    }
  }

  /** Constructs a middleware that will check for the csrf token
    * presence on both the proper cookie, and header values.
    *
    * If it is a valid token, it will then embed a new one,
    * to effectively randomize the complete token while
    * avoiding the generation of a new secure random Id, to guard
    * against [BREACH](http://breachattack.com/)
    *
    */
  def validate(predicate: Request => Boolean = _.method.isSafe)
    : Middleware[Request, MaybeResponse, Request, MaybeResponse] = { service =>
    Kleisli[Task, Request, MaybeResponse] { r =>
      filter(predicate, r, service)
    }
  }

  /** Embed a token into a response **/
  def embedNew(res: MaybeResponse): Task[Response] =
    generateToken.map(
      content =>
        res.cata(_.addCookie(Cookie(cookieName, content)),
                 Response(Status.NotFound)))

  /** Middleware to embed a csrf token into routes that do not have one. **/
  def withNewToken: Middleware[Request, MaybeResponse, Request, MaybeResponse] =
    _.andThen(Kleisli(embedNew))

}

object CSRF {

  /** Default method for constructing CSRF middleware **/
  def apply(headerName: String = "X-Csrf-Token",
            cookieName: String = "csrf-token",
            key: SecretKey,
            clock: Clock = Clock.systemUTC()): CSRF =
    new CSRF(headerName, cookieName, key, clock)

  /** Sugar for instantiating a middleware by generating a key **/
  def withGeneratedKey(headerName: String = "X-Csrf-Token",
                       cookieName: String = "csrf-token",
                       clock: Clock = Clock.systemUTC()): Task[CSRF] =
    generateSigningKey().map(apply(headerName, cookieName, _, clock))

  /** Sugar for pre-loading a key **/
  def withKeyBytes(keyBytes: Array[Byte],
                   headerName: String = "X-Csrf-Token",
                   cookieName: String = "csrf-token",
                   clock: Clock = Clock.systemUTC()): Task[CSRF] =
    buildSigningKey(keyBytes).map(apply(headerName, cookieName, _, clock))

  /** An instance of SecureRandom to generate
    * tokens, properly seeded:
    * https://tersesystems.com/blog/2015/12/17/the-right-way-to-use-securerandom/
    */
  private val CachedRandom = {
    val r = new SecureRandom()
    r.nextBytes(new Array[Byte](20))
    r
  }

  val SigningAlgo = "HmacSHA1"
  val SHA1ByteLen = 20
  val CSRFTokenLength = 32

  private[middleware] def liftedCookieFromHeader(
      request: Request,
      cookieName: String): OptionT[Task, Cookie] =
    OptionT(
      Task.now(
        HCookie
          .from(request.headers)
          .flatMap(_.values.find(_.name == cookieName))))

  private[middleware] def cookieFromHeader(
      request: Request,
      cookieName: String
  ): Option[Cookie] =
    HCookie
      .from(request.headers)
      .flatMap(_.values.find(_.name == cookieName))

  /** A Constant-time string equality **/
  def isEqual(s1: String, s2: String): Boolean =
    MessageDigest.isEqual(s1.getBytes(StandardCharsets.UTF_8),
                          s2.getBytes(StandardCharsets.UTF_8))

  /** Generate an unsigned CSRF token from a `SecureRandom` **/
  private[middleware] def genTokenString: String = {
    val bytes = new Array[Byte](CSRFTokenLength)
    CachedRandom.nextBytes(bytes)
    new String(encodeHex(bytes))
  }

  /** Generate a signing Key for the CSRF token **/
  def generateSigningKey(): Task[SecretKey] =
    Task.delay(KeyGenerator.getInstance(SigningAlgo).generateKey())

  /** Build a new HMACSHA1 Key for our CSRF Middleware
    * from key bytes. This operation is unsafe, in that
    * any amount less than 20 bytes will throw an exception when loaded
    * into `Mac`, and any value above will be truncated (not good for entropy).
    *
    * Use for loading a key from a config file, after having generated
    * one safely
    *
    */
  def buildSigningKey(array: Array[Byte]): Task[SecretKey] =
    Task.delay(new SecretKeySpec(array.slice(0, SHA1ByteLen), SigningAlgo))

}
