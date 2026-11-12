use core_domain::{DomainError, DomainResult};
use serde::{Deserialize, Serialize};
use wasmi::{Caller, Config, Engine, Func, Linker, Module, Store};

pub const MAX_WASM_PAGES: u32 = 16; // 1 MB limit (64 KB * 16)
pub const DEFAULT_FUEL_BUDGET: u64 = 100_000;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModExecutionResult {
    pub success: bool,
    pub output_message: Option<String>,
    pub logs: Vec<String>,
    pub error: Option<String>,
}

#[derive(Default)]
pub struct HostState {
    pub logs: Vec<String>,
    pub last_output: Option<String>,
    pub input_data: String,
}

pub struct ModRuntime {
    engine: Engine,
}

impl Default for ModRuntime {
    fn default() -> Self {
        Self::new()
    }
}

impl ModRuntime {
    pub fn new() -> Self {
        let mut config = Config::default();
        config.consume_fuel(true);
        let engine = Engine::new(&config);
        Self { engine }
    }

    pub fn execute(
        &self,
        wasm_bytes: &[u8],
        entry_point: &str,
        input_json: &str,
    ) -> DomainResult<ModExecutionResult> {
        let module = Module::new(&self.engine, wasm_bytes)
            .map_err(|e| DomainError::InvalidInput(format!("Wasm validation error: {e}")))?;

        let mut store = Store::new(
            &self.engine,
            HostState {
                logs: Vec::new(),
                last_output: None,
                input_data: input_json.to_string(),
            },
        );
        store
            .set_fuel(DEFAULT_FUEL_BUDGET)
            .map_err(|e| DomainError::Internal(format!("failed to set fuel: {e}")))?;

        let mut linker = Linker::<HostState>::new(&self.engine);

        // Host functions for logging and I/O
        linker
            .define(
                "host",
                "log",
                Func::wrap(
                    &mut store,
                    |mut caller: Caller<'_, HostState>, ptr: u32, len: u32| {
                        if let Some(wasmi::Extern::Memory(mem)) = caller.get_export("memory") {
                            let mut buf = vec![0u8; len as usize];
                            if mem.read(&caller, ptr as usize, &mut buf).is_ok() {
                                if let Ok(s) = std::str::from_utf8(&buf) {
                                    caller.data_mut().logs.push(s.to_string());
                                }
                            }
                        }
                    },
                ),
            )
            .map_err(|e| DomainError::Internal(format!("linker error: {e}")))?;

        linker
            .define(
                "host",
                "set_output",
                Func::wrap(
                    &mut store,
                    |mut caller: Caller<'_, HostState>, ptr: u32, len: u32| {
                        if let Some(wasmi::Extern::Memory(mem)) = caller.get_export("memory") {
                            let mut buf = vec![0u8; len as usize];
                            if mem.read(&caller, ptr as usize, &mut buf).is_ok() {
                                if let Ok(s) = std::str::from_utf8(&buf) {
                                    caller.data_mut().last_output = Some(s.to_string());
                                }
                            }
                        }
                    },
                ),
            )
            .map_err(|e| DomainError::Internal(format!("linker error: {e}")))?;

        linker
            .define(
                "host",
                "get_input_len",
                Func::wrap(&mut store, |caller: Caller<'_, HostState>| -> u32 {
                    caller.data().input_data.len() as u32
                }),
            )
            .map_err(|e| DomainError::Internal(format!("linker error: {e}")))?;

        linker
            .define(
                "host",
                "read_input",
                Func::wrap(
                    &mut store,
                    |mut caller: Caller<'_, HostState>, dest_ptr: u32, max_len: u32| -> u32 {
                        let bytes = caller.data().input_data.as_bytes().to_vec();
                        let to_copy = bytes.len().min(max_len as usize);
                        if let Some(wasmi::Extern::Memory(mem)) = caller.get_export("memory") {
                            if mem
                                .write(&mut caller, dest_ptr as usize, &bytes[..to_copy])
                                .is_ok()
                            {
                                return to_copy as u32;
                            }
                        }
                        0
                    },
                ),
            )
            .map_err(|e| DomainError::Internal(format!("linker error: {e}")))?;

        let instance = match linker.instantiate(&mut store, &module) {
            Ok(inst) => match inst.start(&mut store) {
                Ok(inst) => inst,
                Err(trap) => {
                    return Ok(ModExecutionResult {
                        success: false,
                        output_message: None,
                        logs: store.into_data().logs,
                        error: Some(format!("mod start trapped: {trap}")),
                    });
                }
            },
            Err(e) => {
                return Ok(ModExecutionResult {
                    success: false,
                    output_message: None,
                    logs: store.into_data().logs,
                    error: Some(format!("instantiation failed: {e}")),
                });
            }
        };

        // Enforce memory limit if memory exported
        if let Some(wasmi::Extern::Memory(mem)) = instance.get_export(&store, "memory") {
            if mem.size(&store) > MAX_WASM_PAGES {
                return Ok(ModExecutionResult {
                    success: false,
                    output_message: None,
                    logs: store.into_data().logs,
                    error: Some("mod exceeded maximum memory allocation limit".into()),
                });
            }
        }

        // Call the entry point function
        let func = match instance.get_typed_func::<(), i32>(&store, entry_point) {
            Ok(f) => f,
            Err(_) => {
                return Ok(ModExecutionResult {
                    success: false,
                    output_message: None,
                    logs: store.into_data().logs,
                    error: Some(format!(
                        "entry point '{entry_point}' not found or invalid signature"
                    )),
                });
            }
        };

        let call_res = func.call(&mut store, ());
        let host_data = store.into_data();

        match call_res {
            Ok(ret_code) => Ok(ModExecutionResult {
                success: ret_code == 0,
                output_message: host_data.last_output,
                logs: host_data.logs,
                error: if ret_code != 0 {
                    Some(format!("mod returned non-zero exit code: {ret_code}"))
                } else {
                    None
                },
            }),
            Err(trap) => Ok(ModExecutionResult {
                success: false,
                output_message: host_data.last_output,
                logs: host_data.logs,
                error: Some(format!("mod trapped: {trap}")),
            }),
        }
    }
}
