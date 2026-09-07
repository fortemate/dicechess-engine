"""Regenerate the synthetic PDI wiring fixture with Python's onnx package; no training data.

The model computes 0.1 + 0.6 * own_pdi + 0.2 * opponent_pdi. These arbitrary coefficients
exist solely to detect swapped inputs, normalization errors, and single/batch inference drift.
"""

from pathlib import Path

import onnx
from onnx import TensorProto, helper

weights = helper.make_tensor("weights", TensorProto.FLOAT, [11, 1], [0.0] * 9 + [0.6, 0.2])
bias = helper.make_tensor("bias", TensorProto.FLOAT, [1], [0.1])
graph = helper.make_graph(
    [helper.make_node("MatMul", ["input", "weights"], ["weighted"]),
     helper.make_node("Add", ["weighted", "bias"], ["variable"])],
    "synthetic-pdi-wiring",
    [helper.make_tensor_value_info("input", TensorProto.FLOAT, [None, 11])],
    [helper.make_tensor_value_info("variable", TensorProto.FLOAT, [None, 1])],
    [weights, bias],
)
model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], ir_version=8)
onnx.checker.check_model(model)
onnx.save(model, Path(__file__).with_name("synthetic_pdi_test_model.onnx"))
