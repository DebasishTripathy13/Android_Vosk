# Instructions for Model Setup

This folder should contain the Vosk Hindi model.

## Steps to add the model:

1. Download a Hindi Vosk model from: https://alphacephei.com/vosk/models

   Recommended models:
   - **vosk-model-small-hi-0.22** (42 MB) - Lightweight, good for mobile
   - **vosk-model-hi-0.22** (1.5 GB) - Better accuracy, larger size

2. Extract the downloaded ZIP file

3. Rename the extracted folder to `model-hi`

4. Copy the entire `model-hi` folder here

5. Final structure should be:
   ```
   assets/
   └── model-hi/
       ├── am/
       ├── conf/
       ├── graph/
       ├── ivector/
       └── README
   ```

Note: The model files are too large to include in the repository, so you must download them separately.

## Model Download Links:

- Small Hindi Model: https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip
- Full Hindi Model: https://alphacephei.com/vosk/models/vosk-model-hi-0.22.zip
