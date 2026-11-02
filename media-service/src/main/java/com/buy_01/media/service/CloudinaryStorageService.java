package com.buy_01.media.service;

import java.io.IOException;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.buy_01.media.exception.MediaStorageException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CloudinaryStorageService {
    private final Cloudinary cloudinary;

    public Map<String, Object> uploadFile(MultipartFile file) {
        // Uploads the file byte array to Cloudinary under the folder 'buy01_products'
        try {
            return cloudinary.uploader().upload(
                file.getBytes(),
                ObjectUtils.asMap(
                        "folder", "buy01_products",
                        "resource_type", "auto"
                )
            );
        } catch (IOException | RuntimeException ex) {
            throw new MediaStorageException(ex);
        }
    }


    public void deleteFile(String publicId) {
        try {
            // Removes image asset from Cloudinary CDN/storage
            cloudinary.uploader().destroy(publicId, ObjectUtils.emptyMap());
        } catch (IOException | RuntimeException ex) {
            throw new MediaStorageException(ex);
        }
    }
    
}
