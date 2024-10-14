const uploadUrl = '<<<upload-url>>>';

document.addEventListener('DOMContentLoaded', (event) => {
  const form = document.getElementById('uploadForm');
  const fileInput = document.getElementById('fileInput');
  const statusDiv = document.getElementById('status');

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const file = fileInput.files[0];
    if (!file) {
      statusDiv.textContent = 'Please select a file first.';
      return;
    }

    statusDiv.textContent = 'Compressing and uploading file...';

    try {
      result = await uploadGzippedFile(file);
      statusDiv.innerHTML = '<span>File uploaded successfully: <a href="/' + result.id + '">' + result.id + '</a></span>';
    } catch (error) {
      statusDiv.textContent = `Upload failed: ${error.message}`;
    }
  });
});

async function uploadGzippedFile(file) {
  if (typeof CompressionStream === 'undefined') {
    throw new Error('CompressionStream is not supported in this browser.');
  }

  let compressedStream = file.stream().pipeThrough(new CompressionStream('gzip'));
  let compressedBlob = await new Response(compressedStream).blob();

  let response = await fetch(uploadUrl, {
    method: 'POST',
    body: compressedBlob,
    headers: {
      'Content-Type': 'application/gzip',
      'Content-Encoding': 'gzip',
      'X-Filename': file.name
    }});

  if (!response.ok) {
    throw new Error(`HTTP error, status: ${response.status}`);
  }

  const responseBody = JSON.parse(await response.text());
  console.log('Server response:', responseBody);
  return responseBody;
}
