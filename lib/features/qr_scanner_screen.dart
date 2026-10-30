import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

/// Camera access is requested only when the user opens this screen.
class QrScannerScreen extends StatefulWidget {
  const QrScannerScreen({super.key});
  @override
  State<QrScannerScreen> createState() => _QrScannerScreenState();
}
class _QrScannerScreenState extends State<QrScannerScreen> {
  final _controller = MobileScannerController(formats: const [BarcodeFormat.qrCode]);
  bool _returned = false;
  @override
  void dispose() { _controller.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Scan receiver QR')),
    body: Column(children: [
      const Padding(padding: EdgeInsets.all(20), child: Text('Point at the QR shown inside FlashLink on the receiving phone.')),
      Expanded(child: MobileScanner(controller: _controller,
        errorBuilder: (context, error) => Center(child: Padding(padding: const EdgeInsets.all(24), child: Column(mainAxisSize: MainAxisSize.min, children: [
          Text(error.errorCode == MobileScannerErrorCode.permissionDenied
            ? 'Camera permission missing. Allow camera access in Android app settings, or go back and paste the receiver invitation.'
            : 'Camera unavailable (${error.errorCode.name}). Go back and paste the receiver invitation.'),
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Use invitation instead')),
        ]))),
        onDetect: (capture) {
          if (_returned) { return; }
          for (final barcode in capture.barcodes) {
            final value = barcode.rawValue;
            if (value != null && value.startsWith('flashlink://pair?')) {
              _returned = true;
              Navigator.pop(context, value);
              return;
            }
          }
        })),
    ]),
  );
}
