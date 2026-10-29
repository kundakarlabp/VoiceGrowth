/**
 * VoiceGrowth Google Apps Script: private transcript inventory only.
 *
 * One-time setup in a personal Apps Script project:
 * Script Properties:
 *   VOICEGROWTH_TRANSCRIPTS_ID = exact ID of the canonical PRIVATE Transcripts folder
 *   VOICEGROWTH_REGISTRY_SHEET_ID = exact KBP Intelligence Dashboard sheet ID
 * Run setupVoiceGrowthTranscriptWatcher() ONCE and approve Google permissions.
 *
 * No AI API, audio access, patient text ingestion or additional subscription.
 * Never change clinical review/verification columns in this script.
 */
function setupVoiceGrowthTranscriptWatcher() {
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty('VOICEGROWTH_TRANSCRIPTS_ID') ||
      !props.getProperty('VOICEGROWTH_REGISTRY_SHEET_ID')) {
    throw new Error('Configure both Script Properties before creating a trigger');
  }
  const handler = 'scanVoiceGrowthTranscriptMetadata';
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === handler)
    .forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger(handler).timeBased().everyMinutes(15).create();
  scanVoiceGrowthTranscriptMetadata();
}

function scanVoiceGrowthTranscriptMetadata() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(10000)) return;
  try {
    const props = PropertiesService.getScriptProperties();
    const folderId = props.getProperty('VOICEGROWTH_TRANSCRIPTS_ID');
    const spreadsheetId = props.getProperty('VOICEGROWTH_REGISTRY_SHEET_ID');
    if (!folderId || !spreadsheetId) throw new Error('Missing VoiceGrowth Script Properties');
    const sheet = SpreadsheetApp.openById(spreadsheetId).getSheetByName('VG Inbox');
    if (!sheet) throw new Error('VG Inbox tab not found');
    const last = sheet.getLastRow();
    if (last < 2) return;

    // Read only the minimal matching fields; do not access transcript bodies.
    const rows = sheet.getRange(2, 1, last - 1, 19).getValues();
    const header = sheet.getRange(1, 1, 1, 19).getValues()[0];
    const mustBe = {2:'ArchiveFilename',7:'TranscriptionStatus',
      8:'ClinicalReviewStatus',16:'TranscriptDriveFileID',
      17:'TranscriptMatchStatus',18:'TranscriptCheckedAt'};
    Object.keys(mustBe).forEach(k => {
      if (header[Number(k)] !== mustBe[k]) throw new Error('Registry column layout changed');
    });

    const byAudioName = new Map();
    rows.forEach((r, i) => {
      const name = String(r[2] || '').trim();
      if (!name) return;
      const list = byAudioName.get(name) || [];
      list.push(i + 2);
      byAudioName.set(name, list);
    });

    const transcripts = new Map();
    const queue = [DriveApp.getFolderById(folderId)];
    while (queue.length) {
      const folder = queue.shift();
      const children = folder.getFolders();
      while (children.hasNext()) queue.push(children.next());
      const files = folder.getFiles();
      while (files.hasNext()) {
        const file = files.next();
        const name = file.getName();
        if (!/^VG_[a-zA-Z0-9_]+ - Transcript\\.md$/.test(name)) continue;
        const existing = transcripts.get(name) || [];
        existing.push(file.getId());
        transcripts.set(name, existing);
      }
    }

    let changed = 0, ambiguous = 0;
    for (const [name, ids] of transcripts.entries()) {
      const basename = name.replace(/ - Transcript\\.md$/, '');
      const candidates = ['m4a','wav','mp3','aac','ogg','flac']
        .flatMap(ext => byAudioName.get(basename + '.' + ext) || []);
      // Fail closed on duplicate audio rows, duplicate cloud transcript copies.
      if (ids.length !== 1 || candidates.length !== 1) { ambiguous++; continue; }
      const row = candidates[0], current = rows[row-2];
      const previousId = String(current[16] || '').trim();
      if (previousId && previousId !== ids[0]) { ambiguous++; continue; }
      const processingStatus = String(current[7] || '').trim().toLowerCase();
      const alreadyVerified = processingStatus.includes('verified') ||
        String(current[8] || '').toLowerCase().includes('verified');
      if (alreadyVerified) continue;

      // Q/R/S and optionally H only. Never overwrite clinical-review status (I).
      if (previousId !== ids[0] || !current[17]) {
        sheet.getRange(row, 17, 1, 3).setValues([[
          ids[0], 'Exact filename match; content NOT reviewed', new Date()
        ]]);
        if (processingStatus === 'not started' || processingStatus === '' ||
            processingStatus.startsWith('waiting_')) {
          sheet.getRange(row, 8).setValue('Draft transcript — unverified');
        }
        changed++;
      }
    }
    console.log(JSON.stringify({metadataLinksUpdated:changed,ambiguousSkipped:ambiguous,
      transcriptNamesSeen:transcripts.size}));
  } finally {
    lock.releaseLock();
  }
}
