//! CLI argument parsing tests.

use std::path::PathBuf;

use omniroute_core::parse_args;

#[test]
fn parse_args_defaults() {
    let opts = parse_args(&[]).unwrap();
    assert_eq!(opts.bind, "127.0.0.1");
    assert_eq!(opts.port, 20128);
    assert_eq!(opts.config, PathBuf::from("./omniroute.json"));
}

#[test]
fn parse_args_overrides() {
    let args = vec![
        "--port".to_string(),
        "3000".to_string(),
        "--bind".to_string(),
        "0.0.0.0".to_string(),
        "--config".to_string(),
        "/tmp/x.json".to_string(),
    ];
    let opts = parse_args(&args).unwrap();
    assert_eq!(opts.bind, "0.0.0.0");
    assert_eq!(opts.port, 3000);
    assert_eq!(opts.config, PathBuf::from("/tmp/x.json"));
}

#[test]
fn parse_args_rejects_bad_port() {
    let args = vec!["--port".to_string(), "abc".to_string()];
    assert!(parse_args(&args).is_err());
}