package com.tiendaposs.media;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;
import java.util.Base64;
import java.util.UUID;

/**
 * Fotos de productos, logos y QR de pago en Cloudflare R2 (el mismo almacenamiento de Ecomerxio SaaS, variables STORAGE_*).
 * Sin las llaves queda apagado y las imágenes siguen guardándose como antes: data URL dentro de la base de datos.
 * Los objetos se llaman {prefijo}/{negocio}/{carpeta}/{uuid}: nunca se sobreescriben, así que se cachean para siempre.
 * No se borran al cambiar la foto: una copia de seguridad vieja sigue mostrando sus fotos al restaurarla.
 */
@Service
public class MediaStorage {

    private static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final String endpoint;
    private final String bucket;
    private final String key;
    private final String secret;
    private final String publicBaseUrl;
    private final String prefix;
    private volatile S3Client client;

    public MediaStorage(@Value("${app.storage.url:}") String endpoint, @Value("${app.storage.bucket:}") String bucket,
                        @Value("${app.storage.key:}") String key, @Value("${app.storage.secret:}") String secret,
                        @Value("${app.storage.public-base-url:}") String publicBaseUrl, @Value("${app.storage.prefix:pos}") String prefix) {
        this.endpoint = endpoint.trim();
        this.bucket = bucket.trim();
        this.key = key.trim();
        this.secret = secret.trim();
        this.publicBaseUrl = publicBaseUrl.trim().replaceAll("/+$", "");
        this.prefix = prefix.trim().replaceAll("^/+|/+$", "");
    }

    public boolean enabled() {
        return !endpoint.isEmpty() && !bucket.isEmpty() && !key.isEmpty() && !secret.isEmpty() && !publicBaseUrl.isEmpty();
    }

    /** Origen público de las imágenes (para la política de seguridad del navegador), o null si está apagado. */
    public String publicOrigin() {
        if (!enabled()) return null;
        URI u = URI.create(publicBaseUrl);
        return u.getScheme() + "://" + u.getRawAuthority();
    }

    /** ¿Es una imagen ya subida por este negocio? (la pantalla reenvía la URL tal cual al guardar sin cambiar la foto). */
    public boolean isStored(Long tenantId, String value) {
        return value != null && !publicBaseUrl.isEmpty() && !value.contains("..")
                && value.startsWith(publicBaseUrl + "/" + prefix + "/" + tenantId + "/");
    }

    /**
     * Lo que se guarda en la base: con R2 encendido, una data URL se sube y queda su URL pública; lo demás (null,
     * una URL ya subida, o R2 apagado) se devuelve igual. Quien llama ya validó el formato y el tamaño.
     */
    public String save(Long tenantId, String folder, String value) {
        if (value == null || !enabled() || !value.startsWith("data:")) return value;
        Decoded d = decode(value);
        String objectKey = prefix + "/" + tenantId + "/" + folder + "/" + UUID.randomUUID() + extension(d.contentType());
        client().putObject(PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(d.contentType())
                .cacheControl(IMMUTABLE_CACHE_CONTROL).build(), RequestBody.fromBytes(d.bytes()));
        return publicBaseUrl + "/" + objectKey;
    }

    public record Decoded(byte[] bytes, String contentType) {
    }

    public static Decoded decode(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        String type = dataUrl.substring(5, dataUrl.indexOf(';'));
        return new Decoded(Base64.getDecoder().decode(dataUrl.substring(comma + 1)), type);
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            default -> "";
        };
    }

    private S3Client client() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    client = S3Client.builder()
                            .endpointOverride(URI.create(endpoint))
                            .region(Region.of("auto"))
                            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                            .build();
                }
            }
        }
        return client;
    }
}
