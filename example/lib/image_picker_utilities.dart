import 'dart:io';

import 'package:image_picker/image_picker.dart';

class ImagePickerUtilities {
  static Future<File?> pickSingleImage() async {
    ImagePicker picker = ImagePicker();

    XFile? image =
        await picker.pickImage(source: ImageSource.gallery);
    if (image != null) {
      File file = File(image.path);
      if (await file.exists()) {
        return file;
      }
    }
    return null;
  }
}
