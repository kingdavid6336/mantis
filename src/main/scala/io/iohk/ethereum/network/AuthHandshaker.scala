package io.iohk.ethereum.network

import java.math.BigInteger
import java.net.URI
import java.security.SecureRandom

import akka.util.ByteString
import io.iohk.ethereum.crypto._
import org.spongycastle.crypto.AsymmetricCipherKeyPair
import org.spongycastle.crypto.agreement.ECDHBasicAgreement
import org.spongycastle.crypto.digests.{KeccakDigest, SHA256Digest}
import org.spongycastle.crypto.params.{ECPrivateKeyParameters, ECPublicKeyParameters}
import org.spongycastle.crypto.signers.{HMacDSAKCalculator, ECDSASigner}
import org.spongycastle.math.ec.ECPoint
import scorex.core.network
import scorex.core.network._

class Secrets(
    val aes: Array[Byte],
    val mac: Array[Byte],
    val token: Array[Byte],
    val egressMac: KeccakDigest,
    val ingressMac: KeccakDigest)
  extends scorex.core.network.Secrets

case class AuthHandshaker(
    nodeKey: AsymmetricCipherKeyPair,
    initiated: Boolean = false,
    initiateMessageOpt: Option[AuthInitiateMessage] = None,
    initiatePacketOpt: Option[ByteString] = None,
    responseMessageOpt: Option[AuthResponseMessage] = None,
    responsePacketOpt: Option[ByteString] = None,
    ephemeralKey: AsymmetricCipherKeyPair = generateKeyPair())
  extends scorex.core.network.AuthHandshaker {

  val nonceSize = 32
  val macSize = 256
  val secretSize = 32

  override def initiate(uri: URI): (ByteString, network.AuthHandshaker) = {
    val remotePubKey = publicKeyFromNodeId(uri.getUserInfo)
    val message = createAuthInitiateMessage(remotePubKey)
    val encryptedPacket = ByteString(ECIESCoder.encrypt(remotePubKey, message.encode().toArray, None))
    (encryptedPacket, copy(initiated = true, initiateMessageOpt = Some(message), initiatePacketOpt = Some(encryptedPacket)))
  }

  override def handleResponseMessage(data: ByteString): AuthHandshakeResult = {
    val plaintext = ECIESCoder.decrypt(nodeKey.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD, data.toArray)
    val message = AuthResponseMessage.decode(plaintext)

    copy(responseMessageOpt = Some(message), responsePacketOpt = Some(data)).finalizeHandshake()
  }

  override def handleInitialMessage(data: ByteString): (ByteString, AuthHandshakeResult) = {
    val remotePubKey = curve.getCurve.decodePoint(data.take(64).toArray)

    val plaintext = ECIESCoder.decrypt(nodeKey.getPrivate.asInstanceOf[ECPrivateKeyParameters].getD, data.toArray)
    val message = AuthInitiateMessage.decode(plaintext)

    val response = createAuthResponseMessage()
    val encryptedPacket = ByteString(ECIESCoder.encrypt(remotePubKey, message.encode().toArray, None))

    (encryptedPacket, copy(responseMessageOpt = Some(response), responsePacketOpt = Some(encryptedPacket)).finalizeHandshake())
  }

  private def createAuthInitiateMessage(remotePubKey: ECPoint) = {
    val nonce = new Array[Byte](32)
    new SecureRandom().nextBytes(nonce)

    // TODO: handle the case when the peer is known
    val sessionTokenOpt: Option[Array[Byte]] = None
    val knownPeer = sessionTokenOpt.isDefined

    val sharedSecret = sessionTokenOpt match {
      case Some(sessionToken) => sessionToken
      case None =>
        val agreement = new ECDHBasicAgreement
        agreement.init(nodeKey.getPrivate)
        bigIntegerToBytes(agreement.calculateAgreement(new ECPublicKeyParameters(remotePubKey, curve)), nonceSize)
    }

    val ephemeralPubKey = ECIESPublicKeyEncoder.getEncoded(ephemeralKey.getPublic)
    val ephemeralPublicHash = sha3(ephemeralPubKey, 1, 64)

    val publicKey = nodeKey.getPublic.asInstanceOf[ECPublicKeyParameters].getQ

    val signature = {
      val signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest))
      signer.init(true, ephemeralKey.getPrivate)
      val messageToSign = xor(sharedSecret, nonce)
      val components = signer.generateSignature(messageToSign)
      val r = components(0)
      val s = ECDSASignature.canonicalise(components(1))
      val v = ECDSASignature
        .calculateV(r, s, ephemeralKey, messageToSign)
        .getOrElse(throw new RuntimeException("Failed to calculate signature rec id"))

      ECDSASignature(r, s, v)
    }

    AuthInitiateMessage(signature, ByteString(ephemeralPublicHash), publicKey, ByteString(nonce), knownPeer)
  }

  // TODO: needed to handle incoming peer connections
  private def createAuthResponseMessage() = {
    ???
  }

  private def finalizeHandshake(): AuthHandshakeResult = {

    // TODO: handle the case when it's a known peer (ethereumj doesn't seem to handle that?)

    val successOpt = for {
      initiatePacket <- initiatePacketOpt
      initiateMessage <- initiateMessageOpt
      responsePacket <- responsePacketOpt
      responseMessage <- responseMessageOpt
    } yield {

      val remoteEphemeralKey =
        if (initiated) responseMessage.ephemeralPublicKey
        else {
          // TODO: needs testing (the case when this node is not initiator)
          val agreement = new ECDHBasicAgreement
          agreement.init(nodeKey.getPrivate)
          val sharedSecret = agreement.calculateAgreement(new ECPublicKeyParameters(initiateMessage.publicKey, curve))

          val token: Array[Byte] = bigIntegerToBytes(sharedSecret, nonceSize)
          val signed: Array[Byte] = xor(token, initiateMessage.nonce.toArray)

          val signaturePubBytes =
            ECDSASignature.recoverPubBytes(
              r = initiateMessage.signature.r,
              s = initiateMessage.signature.s,
              recId = ECDSASignature.recIdFromSignatureV(initiateMessage.signature.v),
              message = signed).get

          curve.getCurve.decodePoint(signaturePubBytes)
        }

      val secretScalar = {
        val agreement = new ECDHBasicAgreement
        agreement.init(ephemeralKey.getPrivate)
        agreement.calculateAgreement(new ECPublicKeyParameters(remoteEphemeralKey, curve))
      }

      val agreedSecret = bigIntegerToBytes(secretScalar, secretSize)
      val sharedSecret = sha3(agreedSecret, sha3(responseMessage.nonce.toArray, initiateMessage.nonce.toArray))

      val aesSecret = sha3(agreedSecret, sharedSecret)

      val macSecret = sha3(agreedSecret, aesSecret)

      val mac1 = new KeccakDigest(macSize)
      mac1.update(xor(macSecret, responseMessage.nonce.toArray), 0, macSecret.length)

      val buf = new Array[Byte](32)
      new KeccakDigest(mac1).doFinal(buf, 0)
      mac1.update(initiatePacket.toArray, 0, initiatePacket.toArray.length)
      new KeccakDigest(mac1).doFinal(buf, 0)
      val mac2 = new KeccakDigest(macSize)

      mac2.update(xor(macSecret, initiateMessage.nonce.toArray), 0, macSecret.length)
      new KeccakDigest(mac2).doFinal(buf, 0)
      mac2.update(responsePacket.toArray, 0, responsePacket.toArray.length)
      new KeccakDigest(mac2).doFinal(buf, 0)

      val (egressMacSecret, ingressMacSecret) =
        if (initiated) (mac1, mac2)
        else (mac2, mac1)

      AuthHandshakeSuccess(new Secrets(
        aes = aesSecret,
        mac = sha3(agreedSecret, aesSecret),
        token = sha3(sharedSecret),
        egressMac = egressMacSecret,
        ingressMac = ingressMacSecret))
    }

    successOpt getOrElse AuthHandshakeError
  }

  private def bigIntegerToBytes(b: BigInteger, numBytes: Int): Array[Byte] = {
    val bytes = new Array[Byte](numBytes)
    val biBytes = b.toByteArray
    val start = if (biBytes.length == numBytes + 1) 1 else 0
    val length = Math.min(biBytes.length, numBytes)
    System.arraycopy(biBytes, start, bytes, numBytes - length, length)
    bytes
  }
}