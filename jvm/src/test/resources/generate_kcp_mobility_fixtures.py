"""Generate synthetic ONNX test models for kcp-mobility-27-v1 and kcp-mobility-pawns-31-v1.

These models have linear graph (MatMul + Add) with exact input dimensions (27 and 31).
They exist solely to test wiring, single/batch inference, and shape-mismatch rejection.
"""

from pathlib import Path
import onnx
from onnx import TensorProto, helper

def make_linear_model(name: str, num_inputs: int, weight_map: dict[int, float], bias_val: float) -> onnx.ModelProto:
    w_list = [0.0] * num_inputs
    for idx, val in weight_map.items():
        w_list[idx] = val
    weights = helper.make_tensor("weights", TensorProto.FLOAT, [num_inputs, 1], w_list)
    bias = helper.make_tensor("bias", TensorProto.FLOAT, [1], [bias_val])
    graph = helper.make_graph(
        [
            helper.make_node("MatMul", ["input", "weights"], ["weighted"]),
            helper.make_node("Add", ["weighted", "bias"], ["variable"]),
        ],
        name,
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, [None, num_inputs])],
        [helper.make_tensor_value_info("variable", TensorProto.FLOAT, [None, 1])],
        [weights, bias],
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 13)], ir_version=8)
    onnx.checker.check_model(model)
    return model

base_dir = Path(__file__).parent

# 27-input model: weights on own_pdi (col 25) = 0.6, opp_pdi (col 26) = 0.2, bias = 0.1
m27 = make_linear_model("synthetic-kcp-mobility-27", 27, {25: 0.6, 26: 0.2}, 0.1)
onnx.save(m27, base_dir / "synthetic_kcp_mobility_27_test_model.onnx")

# 31-input model: weights on own_pdi (col 25) = 0.6, opp_pdi (col 26) = 0.2, own_passed_pawns (col 27) = 0.5, bias = 0.1
m31 = make_linear_model("synthetic-kcp-mobility-pawns-31", 31, {25: 0.6, 26: 0.2, 27: 0.5}, 0.1)
onnx.save(m31, base_dir / "synthetic_kcp_mobility_pawns_31_test_model.onnx")

print("Generated synthetic ONNX models successfully.")
