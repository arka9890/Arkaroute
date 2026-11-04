#[test]
fn reports_package_version() {
    assert_eq!(omniroute_core::VERSION, env!("CARGO_PKG_VERSION"));
}
