# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-11-23

### Changed

- A rule may have `app_version` (the range core resolved from its `requires`: per OS
  `{ "min": …, "exclude": [ … ] }`) and the keys `requires` and `exclude_app_versions`, which the
  device does not read. A rule needs `when`, `app_version` or both; any other key, or a range that
  does not read, makes the flag invalid. A rule outside its range is skipped.
- An experiment may have `app_version` too: outside it, the flag gives the default value and sends
  no `config_exposed`.

## [0.1.0] - 2026-11-22

### Added

- `config-core`: typed flags (`AppConfig.bool`, `int`, `double`, `string`, `json`) with the app's
  default, rules on device values, experiments with weighted variants (the owner's devices get the
  first variant), `config_exposed` once per key per launch and `config_rejected` once per key, reason
  and set version, a set fixed for the launch (`ConfigSession`), the set shipped in the app
  (`BundledConfigSetSource`), storage (`FileConfigStorage`, `InMemoryConfigStorage`) and the fetch
  port (`ConfigSetSource`).
- `config-testing`: `FakeConfigSetSource` and `ConfigEventRecorder`.
- Passes every table in `schema/cases/config` and the variant vectors in `schema/cases/experiment`.
