# Remote voice-package inspection

No large chat upload is required. The separate **Inspect Roxy voice archive** workflow downloads a public candidate archive on its runner, checks the Hugging Face published SHA-256 and creates a small JSON artifact. It does not change Android code, execute the checkpoint, install model dependencies, convert the voice, or commit downloaded weights.

Candidate: https://huggingface.co/juuxn/RVCModels/resolve/main/RoxanneWolf.zip
Published SHA-256: `99b2139f18d7375ebc463b40b1356ba1ca67bbe28978eeea7f4cd3b4a2e02ac6`. The filename matches the supplied AIVoices listing, but its exact outbound destination has not yet been confirmed. Do not silently substitute this model for a different intended source. Hash verification establishes byte identity with this candidate, not authorship, safety or licensing.

The workflow runs on pushes changing its own YAML or inspection script, and can also be run manually from Actions. It has read-only repository permissions and does not persist checkout credentials. Runtime is limited to ten minutes; normal GitHub Actions runner usage may apply.

After it runs: open Actions > Inspect Roxy voice archive > the completed run > artifact **roxy-voice-inspection**. Share the small `voice-inspection.json` here, or paste the relevant metadata windows and archive filenames. The GitHub connector currently available in this conversation does not expose Actions artifact retrieval.

Inspection uses ZIP readers and `pickletools` opcode parsing, never `pickle.load`, `torch.load`, imports from the checkpoint, or inference. The metadata windows are raw static evidence, not guaranteed decoded configuration values. Legacy checkpoints may not expose their full configuration through this scan. Stored filenames are inventoried only; no archive member is extracted to disk.

This is a diagnostic tool, not a voice integration patch. Next steps depend on the report: confirm the actual checkpoint architecture/configuration and index availability, then establish a compatible local inference pipeline and test one short sentence on the S25 Ultra. Keep the approved overlay and wake-word timing unchanged.
